#!/usr/bin/env python3
"""Ejecuta los oráculos RC-1 de H2/H3 contra el adaptador real Java."""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess


def entero(stream, valor):
    stream.write(struct.pack('>i', valor))


def texto(stream, valor):
    if valor is None:
        entero(stream, -1)
    else:
        datos = str(valor).encode('utf-8')
        entero(stream, len(datos))
        stream.write(datos)


def lee_entero(stream):
    return struct.unpack('>i', stream.read(4))[0]


def lee_texto(stream):
    n = lee_entero(stream)
    return None if n == -1 else stream.read(n).decode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    raiz = Path(__file__).resolve().parent.parent
    jar = args.jar or raiz / 'build' / 'AnalizadorLexSint.jar'
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin' / 'java') if java_home else shutil.which('java')
    corpus = json.loads((raiz / 'runtime' / 'especificacion' / 'consola_v1.json').read_text())['cases']
    pendientes = {
        'stdout_pipe_roto': 'Prueba física cubierta por tools/probar_runtime_nativo.py (H4)',
    }
    casos = [c for c in corpus if c['id'] not in pendientes]
    solicitud = io.BytesIO()
    entero(solicitud, len(casos))
    for caso in casos:
        entrada = caso['stdin']
        datos = bytes.fromhex(entrada['hex']) if 'hex' in entrada else bytes.fromhex(entrada['repeat_hex']) * entrada['count'] + bytes.fromhex(entrada.get('suffix_hex', ''))
        if 'delivery' in caso:
            assert b''.join(bytes.fromhex(s) for s in caso['delivery']['chunks_hex']) == datos
        entero(solicitud, len(datos))
        solicitud.write(datos)
        inject = caso.get('inject', {})
        texto(solicitud, inject.get('kind'))
        entero(solicitud, inject.get('operation_index', -1))
        entero(solicitud, inject.get('after_payload_bytes', 0))
        entero(solicitud, len(caso['operations']))
        for op in caso['operations']:
            argumento = op.get('argument', {})
            texto(solicitud, op['method'])
            texto(solicitud, argumento.get('type'))
            valor = argumento.get('value')
            if argumento.get('type') == 'list<int>':
                valor = ','.join(valor)
            elif argumento.get('type') in ('objeto', 'Consola'):
                valor = valor['dynamic_class']
            texto(solicitud, str(valor).lower() if isinstance(valor, bool) else valor)
    resultado = subprocess.run([java, '-cp', str(jar), 'SondaConsola'], input=solicitud.getvalue(), capture_output=True, timeout=30, check=True)
    respuesta = io.BytesIO(resultado.stdout)
    for caso in casos:
        esperado = caso['expected']
        escrito = respuesta.read(lee_entero(respuesta))
        error = lee_texto(respuesta)
        indice_error = lee_entero(respuesta)
        retornos = []
        for _ in range(lee_entero(respuesta)):
            indice = lee_entero(respuesta)
            valor = lee_texto(respuesta)
            bits = struct.unpack('>Q', respuesta.read(8))[0]
            retornos.append((indice, valor, bits))
        assert escrito.hex() == esperado['stdout_hex'], (caso['id'], 'stdout', escrito[:100])
        e = esperado['error']
        assert error == (e['code'] if e else None), (caso['id'], 'error', error)
        assert indice_error == (e['operation_index'] if e else -1), (caso['id'], 'índice de error')
        assert (1 if error else 0) == esperado['exit_status'], (caso['id'], 'estado')
        assert len(retornos) == len(esperado['returns']), (caso['id'], 'retornos')
        for actual, r in zip(retornos, esperado['returns']):
            valor = r.get('value')
            if 'value_repeat' in r:
                v = r['value_repeat']
                valor = (bytes.fromhex(v['hex']) * v['count']).decode('utf-8')
            if isinstance(valor, bool):
                valor = str(valor).lower()
            assert actual[:2] == (r['operation_index'], valor), (caso['id'], 'valor', actual[:2])
            if 'binary64_bits_hex' in r:
                assert actual[2] == int(r['binary64_bits_hex'], 16), (caso['id'], 'bits binary64')
    assert not respuesta.read(), 'Datos inesperados en el protocolo'
    print(f'OK: {len(casos)}/{len(corpus)} oráculos RC-1 ejecutados en el adaptador del intérprete.')
    for caso, razon in pendientes.items():
        print(f'SOLO_NATIVO: {caso}: {razon}')


if __name__ == '__main__':
    main()
