#!/usr/bin/env python3
"""Pruebas H4: oráculos RC-1, formato Java11, memoria, errores y puentes ABI."""
import argparse
import ctypes as c
import hashlib
import zipfile
import json
import os
import platform
from pathlib import Path
import random
import shutil
import struct
import subprocess
import tempfile
from compilar_runtime import compilar


class Error(c.Structure):
    _fields_ = [('code', c.c_uint32), ('system_errno', c.c_int32), ('byte_offset', c.c_uint64)]


class Origin(c.Structure):
    _fields_ = [('file', c.c_char_p), ('line', c.c_int64), ('column', c.c_int64)]


class Type(c.Structure):
    pass


class Class(c.Structure):
    pass


Type._fields_ = [('kind', c.c_uint64), ('element', c.POINTER(Type)), ('klass', c.POINTER(Class))]
Class._fields_ = [('name', c.c_void_p), ('size', c.c_uint64), ('vtable', c.c_void_p),
                 ('base', c.POINTER(Class)), ('field_count', c.c_uint64), ('field_types', c.c_void_p)]


CODES = {1: 'FIN_DE_ENTRADA', 2: 'ENTRADA_INVALIDA', 3: 'ENTRADA_FUERA_DE_RANGO',
         4: 'UTF8_INVALIDO', 5: 'LINEA_DEMASIADO_LARGA', 6: 'ERROR_LECTURA_CONSOLA',
         7: 'ERROR_ESCRITURA_CONSOLA', 8: 'MEMORIA_AGOTADA'}
ORIGIN = Origin(b'oraculo', 1, 1)


def configure(path):
    lib = c.CDLL(str(path))
    signatures = {
        'rt_probe_begin': ([c.c_void_p, c.c_size_t, c.c_int, c.c_int, c.c_size_t], None),
        'rt_probe_operation': ([c.c_int], None),
        'rt_probe_output': ([], c.c_void_p), 'rt_probe_output_length': ([], c.c_size_t),
        'rt_console_v1_init': ([c.POINTER(Error)], c.c_uint32),
        'rt_console_v1_shutdown': ([c.POINTER(Error)], c.c_uint32),
        'rt_console_v1_write': ([c.c_void_p, c.c_uint32, c.POINTER(Error)], c.c_uint32),
        'rt_console_v1_read_line': ([c.POINTER(c.c_void_p), c.POINTER(Error)], c.c_uint32),
        'rt_console_v1_read_int': ([c.POINTER(c.c_int64), c.POINTER(Error)], c.c_uint32),
        'rt_console_v1_read_float': ([c.POINTER(c.c_double), c.POINTER(Error)], c.c_uint32),
        'rt_console_v1_read_bool': ([c.POINTER(c.c_uint32), c.POINTER(Error)], c.c_uint32),
        'rt_string_new': ([c.c_void_p, c.c_uint64, c.POINTER(Origin)], c.c_void_p),
        'rt_value_to_string': ([c.c_uint64, c.POINTER(Type), c.POINTER(Origin)], c.c_void_p),
        'rt_object_new': ([c.POINTER(Class), c.POINTER(Origin)], c.c_void_p),
        'rt_list_new': ([c.c_int64, c.POINTER(Type), c.POINTER(Origin)], c.c_void_p),
        'rt_double_to_java11': ([c.c_double, c.c_void_p], c.c_size_t),
        'rt_memory_release_all': ([], None),
    }
    for name, (args, result) in signatures.items():
        f = getattr(lib, name); f.argtypes = args; f.restype = result
    return lib


def string_bytes(pointer):
    return c.string_at(pointer + 16, c.c_uint64.from_address(pointer).value)


def native_string(lib, data):
    buf = c.create_string_buffer(data)
    return lib.rt_string_new(buf, len(data), c.byref(ORIGIN))


def format_double(lib, value):
    buf = c.create_string_buffer(32); lib.rt_double_to_java11(value, buf)
    return buf.value.decode('ascii')


def value_string(lib, argument):
    if not argument:
        return native_string(lib, b'')
    kind, value = argument['type'], argument.get('value')
    kinds = {'int': 1, 'float': 2, 'bool': 3, 'string': 4, 'null': 5, 'objeto': 6,
             'Consola': 6, 'list<int>': 7}
    t = Type(kinds[kind], None, None)
    descriptor = None  # mantener vivos descriptores prestados hasta terminar el formato
    if kind == 'float':
        bits = struct.unpack('=Q', struct.pack('=d', float(value)))[0]
    elif kind in ('null',):
        bits = 0
    elif kind == 'string':
        bits = native_string(lib, value.encode('utf-8')) if value is not None else 0
    elif kind in ('objeto', 'Consola'):
        descriptor = Class(native_string(lib, value['dynamic_class'].encode()), 16, None, None, 0, None)
        t.klass = c.pointer(descriptor); bits = lib.rt_object_new(c.byref(descriptor), c.byref(ORIGIN))
    elif kind == 'list<int>':
        element = Type.in_dll(lib, 'rt_type_int'); t.element = c.pointer(element)
        bits = lib.rt_list_new(len(value), c.byref(element), c.byref(ORIGIN))
        for i, v in enumerate(value):
            c.c_uint64.from_address(bits + 16 + i * 8).value = int(v) & ((1 << 64) - 1)
    else:
        bits = int(value) & ((1 << 64) - 1)
    return lib.rt_value_to_string(bits, c.byref(t), c.byref(ORIGIN))


def console_case(lib, case):
    src = case['stdin']
    data = bytes.fromhex(src['hex']) if 'hex' in src else bytes.fromhex(src['repeat_hex']) * src['count'] + bytes.fromhex(src.get('suffix_hex', ''))
    if 'delivery' in case:
        assert b''.join(bytes.fromhex(b) for b in case['delivery']['chunks_hex']) == data
    inp = c.create_string_buffer(data); inject = case.get('inject', {})
    kinds = {None: 0, 'read_error': 1, 'write_error': 2, 'flush_error': 3, 'allocation_error': 4, 'eintr': 5, 'partial': 6, 'zero_write': 7}
    lib.rt_probe_begin(inp, len(data), kinds[inject.get('kind')], inject.get('operation_index', -1), inject.get('after_payload_bytes', 0))
    returns, code, index = [], 0, -1
    for i, op in enumerate(case['operations']):
        lib.rt_probe_operation(i); error = Error(99, 99, 99); method = op['method']
        if method.startswith('imprimir'):
            p = value_string(lib, op.get('argument'))
            code = lib.rt_console_v1_write(p, int(method == 'imprimirLinea'), c.byref(error))
        else:
            typ = {'leerInt': c.c_int64, 'leerFloat': c.c_double, 'leerBool': c.c_uint32, 'leerLinea': c.c_void_p}[method]
            result = typ(123)  # resultado debe permanecer intacto ante fallo
            before = bytes(result)
            fn = getattr(lib, {'leerInt': 'rt_console_v1_read_int', 'leerFloat': 'rt_console_v1_read_float',
                               'leerBool': 'rt_console_v1_read_bool', 'leerLinea': 'rt_console_v1_read_line'}[method])
            code = fn(c.byref(result), c.byref(error))
            if code == 0 or method == 'leerLinea' and code == 1:
                value = result.value
                if method == 'leerLinea': value = string_bytes(value).decode() if value else None
                elif method == 'leerBool': value = bool(value)
                elif method == 'leerFloat': value = format_double(lib, value)
                else: value = str(value)
                bits = struct.unpack('=Q', bytes(result))[0] if method == 'leerFloat' else 0
                returns.append((i, value, bits)); code = 0
            else:
                assert bytes(result) == before, (case['id'], 'out modificado en fallo')
        assert error.code == (1 if method == 'leerLinea' and error.code == 1 else code), case['id']
        if code:
            index = i; break
        assert error.system_errno == 0 and error.byte_offset == 0, (case['id'], 'descriptor éxito')
    want = case['expected']; err = want['error']
    output = c.string_at(lib.rt_probe_output(), lib.rt_probe_output_length()) if lib.rt_probe_output_length() else b''
    assert output.hex() == want['stdout_hex'], (case['id'], 'stdout', output[:100])
    assert CODES.get(code) == (err['code'] if err else None), (case['id'], 'error', code)
    assert index == (err['operation_index'] if err else -1), (case['id'], 'operación')
    assert int(code != 0) == want['exit_status'] and len(returns) == len(want['returns']), case['id']
    for got, expected in zip(returns, want['returns']):
        value = expected.get('value')
        if 'value_repeat' in expected:
            item = expected['value_repeat']; value = (bytes.fromhex(item['hex']) * item['count']).decode()
        assert got[:2] == (expected['operation_index'], value), (case['id'], 'retorno', got[:2])
        if 'binary64_bits_hex' in expected:
            assert got[2] == int(expected['binary64_bits_hex'], 16), (case['id'], 'bits')
    lib.rt_console_v1_shutdown(c.byref(Error())); lib.rt_memory_release_all()


def abi_error_cases(lib):
    count = 0
    # Posición del primer byte inválido, estado/errno y out intacto.
    for data, offset in [(b'\xc0\x80\n', 1), (b'A\xff\n', 2),
                         (b'\xed\xa0\x80\n', 2), (b'\xf4\x90\x80\x80\n', 2),
                         (b'A\xe2\x82\n', 2), (b'\x80\n', 1)]:
        inp = c.create_string_buffer(data); lib.rt_probe_begin(inp, len(data), 0, -1, 0)
        out = c.c_void_p(123); e = Error(99, 99, 99)
        assert lib.rt_console_v1_read_line(c.byref(out), c.byref(e)) == 4
        assert (e.code, e.system_errno, e.byte_offset, out.value) == (4, 0, offset, 123)
        lib.rt_console_v1_shutdown(c.byref(Error())); count += 1
    # UTF-8 inválido previo no debe ocultar fallo físico de la línea incompleta.
    inp = c.create_string_buffer(b'\xffx\n'); lib.rt_probe_begin(inp, 3, 1, 0, 2)
    out = c.c_void_p(123); e = Error(); lib.rt_probe_operation(0)
    assert lib.rt_console_v1_read_line(c.byref(out), c.byref(e)) == 6
    assert e.system_errno != 0 and e.byte_offset == 0 and out.value == 123; count += 1
    lib.rt_console_v1_shutdown(c.byref(Error()))
    # EOF persistente y separación entre líneas escalares/leerLinea.
    console_case(lib, {'id': 'eof_repetido', 'stdin': {'hex': ''},
                      'operations': [{'method': 'leerLinea'}] * 3,
                      'expected': {'stdout_hex': '', 'exit_status': 0,
                                   'returns': [{'operation_index': i, 'value': None} for i in range(3)], 'error': None}})
    count += 1
    for text, expected in [('0e999999999999999999999', '0.0'),
                           ('-0e999999999999999999999', '-0.0'),
                           ('1e-999999999999999999999', '0.0'),
                           ('9223372036854775808x', None)]:
        integer = text.endswith('x')
        console_case(lib, {'id': text, 'stdin': {'hex': (text + '\n').encode().hex()},
                          'operations': [{'method': 'leerInt' if integer else 'leerFloat'}],
                          'expected': {'stdout_hex': '', 'exit_status': int(integer),
                                       'returns': [] if integer else [{'operation_index': 0, 'value': expected}],
                                       'error': {'code': 'ENTRADA_INVALIDA', 'operation_index': 0} if integer else None}})
        count += 1
    console_case(lib, {'id': 'write_cero', 'stdin': {'hex': ''}, 'inject': {'kind': 'zero_write'},
                      'operations': [{'method': 'imprimir', 'argument': {'type': 'string', 'value': 'a'}}],
                      'expected': {'stdout_hex': '', 'exit_status': 1, 'returns': [],
                                   'error': {'code': 'ERROR_ESCRITURA_CONSOLA', 'operation_index': 0}}})
    return count + 1


def fatal_cases(path, prefix=(), env=None, timeout=5):
    cases = {'pipe': 'ERROR_ESCRITURA_CONSOLA', 'stderr': None, 'null': 'REFERENCIA_NULA',
             'index': 'INDICE_FUERA_DE_RANGO', 'index_null': 'REFERENCIA_NULA',
             'div': 'DIVISION_POR_CERO', 'div_float': 'DIVISION_POR_CERO',
             'memory': 'MEMORIA_AGOTADA', 'size': 'MEMORIA_AGOTADA', 'read': 'ENTRADA_INVALIDA'}
    # Mismo escape que ImpresorIr, incluyendo surrogates de nombres Unicode.
    escaped = '"ruta\\n\\"ñ\\ud83d\\ude00\\t\\\\\\u007f\\u0080":3:7'
    for mode, code in cases.items():
        result = subprocess.run([*prefix, str(path), mode], input=b'mal\n', capture_output=True, timeout=timeout, env=env)
        assert result.returncode == 1 and result.stdout == b'', (mode, result)
        if code:
            expected = f'Error de ejecución [{code}] {escaped}: {code}\n'.encode()
            assert result.stderr == expected, (mode, result.stderr, expected)
        else:
            assert not result.stderr, result.stderr
    result = subprocess.run([*prefix, str(path), 'signal'], capture_output=True, timeout=timeout, env=env)
    assert result.returncode == 0 and not result.stdout and not result.stderr, result
    return len(cases) + 1


def float_cases(lib, raiz, jar, java):
    corpus = json.loads((raiz / 'runtime/especificacion/float_java11_v1.json').read_text())
    with zipfile.ZipFile(jar) as package:
        assert hashlib.sha256(package.read('runtime/jdk11/FloatingDecimal.java')).hexdigest() == corpus['source_sha256'], 'Referencia Java11 del JAR distinta del corpus fijado'
    for case in corpus['cases']:
        bits = int(case['bits'], 16); value = struct.unpack('>d', bits.to_bytes(8, 'big'))[0]
        assert format_double(lib, value) == case['text'], case
    # Corpus ampliado independiente del puerto C, manteniendo fijo el código Java11.
    rng = random.Random(20261010); bits = [rng.getrandbits(64) for _ in range(100000)]
    process = subprocess.run([java, '-cp', str(jar), 'SondaFloatJava11'],
                             input=''.join(f'{b:016x}\n' for b in bits), text=True, capture_output=True, timeout=30, check=True)
    lines = process.stdout.splitlines(); assert len(lines) == len(bits)
    for line in lines:
        h, expected = line.split('\t'); value = struct.unpack('>d', int(h, 16).to_bytes(8, 'big'))[0]
        assert format_double(lib, value) == expected, (h, expected, format_double(lib, value))
    return len(corpus['cases']), len(bits)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--target', choices=['linux-x64','windows-x64'], default='linux-x64')
    parser.add_argument('--cc')
    parser.add_argument('--ar')
    parser.add_argument('--sanitizers', action='store_true', help='Requiere libasan/libubsan instaladas')
    args = parser.parse_args()
    raiz = Path(__file__).resolve().parent.parent; jar = args.jar or raiz / 'build/AnalizadorLexSint.jar'
    java_home = os.environ.get('JAVA_HOME'); java = str(Path(java_home) / 'bin' / ('java.exe' if os.name=='nt' else 'java')) if java_home else shutil.which('java')
    windows=args.target=='windows-x64'
    if windows and platform.system()!='Windows':
        raise SystemExit('Los 90 oráculos Windows requieren Python y ejecución nativa en Windows; usa probar_h5.py --wine para la comprobación cruzada adicional.')
    if windows and args.sanitizers: raise SystemExit('Sanitizers solo disponible en el harness Linux.')
    cc=args.cc or os.environ.get('CC_WINDOWS' if windows else 'CC','gcc')
    with tempfile.TemporaryDirectory(prefix='pruebas-h4-') as tmp:
        destino = compilar(raiz, Path(tmp), pruebas=True, target=args.target, cc=cc, ar=args.ar); lib = configure(destino / ('libsonda_runtime.dll' if windows else 'libsonda_runtime.so'))
        cases = json.loads((raiz / 'runtime/especificacion/consola_v1.json').read_text())['cases']
        for case in cases:
            if case['id'] != 'stdout_pipe_roto': console_case(lib, case)
        adicionales = abi_error_cases(lib)
        fatales = fatal_cases(destino / ('sonda_fatal.exe' if windows else 'sonda_fatal'))
        for kind in ('eintr', 'partial'):
            console_case(lib, {'id': kind, 'stdin': {'hex': '31320a'}, 'inject': {'kind': kind},
                              'operations': [{'method': 'imprimirLinea', 'argument': {'type': 'string', 'value': 'abc'}}, {'method': 'leerInt'}],
                              'expected': {'stdout_hex': '6162630a', 'exit_status': 0, 'returns': [{'operation_index': 1, 'value': '12'}], 'error': None}})
        print(f'OK: {len(cases)}/{len(cases)} oráculos RC-1 nativos; {adicionales} casos ABI adicionales, {fatales} rutas de finalización y EINTR/escrituras parciales.')
        fijo, differential = float_cases(lib, raiz, jar, java)
        print(f'OK: {fijo} casos float versionados + {differential} patrones binary64 diferenciales contra OpenJDK11 fijado.')
        # Pruebas C de representación y memoria; sanitizadores opcionales en proceso separado.
        sources = sorted((raiz / 'runtime/nativo/src').glob('*.c'))
        exe = destino / ('selftest.exe' if windows else 'selftest')
        sanitize = ['-fsanitize=address,undefined', '-fno-omit-frame-pointer'] if args.sanitizers else []
        subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror', '-pedantic', '-O1', '-g', '-ffp-contract=off',
                        *sanitize, '-DRT_TESTING', '-I', str(raiz / 'runtime/nativo/include'),
                        str(raiz / 'runtime/nativo/pruebas/selftest.c'), *map(str, sources), '-lm', '-o', str(exe)], check=True)
        subprocess.run([str(exe)], check=True, timeout=30)
        environment = os.environ.copy()
        if not environment.get('NASM'):
            nasm = shutil.which('nasm') or (str(raiz / 'build/tools/nasm') if (raiz / 'build/tools/nasm').is_file() else None)
            if nasm:
                environment['NASM'] = nasm
            else:
                print('SIN_NASM: se ejecuta el soporte GNU as; selecciona NASM para validar también el ensamblador destino.')
        if not windows:
            subprocess.run([java, '-cp', str(jar), 'PruebasH4', str(destino)], check=True, timeout=30, cwd=raiz, env=environment)
        else:
            print('Windows: los puentes del programa completo se validan con tools/probar_h5.py --target windows-x64.')


if __name__ == '__main__':
    main()
