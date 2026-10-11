#!/usr/bin/env python3
"""Compila el runtime C11 por perfil: núcleo compartido y adaptador POSIX/Win32."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile


def ejecutar(comando, **opciones):
    return subprocess.run(comando, check=True, timeout=120, **opciones)


def compilar(raiz, destino, pruebas=False, sanitizers=False, *, target='linux-x64', cc=None, ar=None):
    if target not in ('linux-x64', 'windows-x64'):
        raise SystemExit('Destino desconocido: ' + target)
    windows = target == 'windows-x64'
    cc = cc or os.environ.get('CC_WINDOWS' if windows else 'CC',
                             'gcc' if not windows or platform.system() == 'Windows' else 'x86_64-w64-mingw32-gcc')
    if not shutil.which(cc):
        raise SystemExit('No se encontró el compilador C para ' + target + ': ' + cc)
    machine = ejecutar([cc, '-dumpmachine'], capture_output=True, text=True).stdout.strip()
    if not machine.startswith('x86_64') or (windows and not any(s in machine for s in ('mingw', 'windows'))) or (not windows and 'linux' not in machine):
        raise SystemExit('El compilador ' + cc + ' no corresponde a ' + target + ': ' + machine)
    if windows:
        # Detectar el CRT efectivo en headers; el nombre gcc no garantiza UCRT.
        ejecutar([cc, '-x', 'c', '-E', '-'], input='#include <_mingw.h>\n#ifndef _UCRT\n#error El perfil windows-x64 exige UCRT\n#endif\n', text=True, stdout=subprocess.DEVNULL)
    if sanitizers and windows:
        raise SystemExit('El harness de sanitizers actual requiere linux-x64.')
    if ar is None:
        ar = os.environ.get('AR_WINDOWS' if windows else 'AR')
    if ar is None and windows:
        candidate = Path(cc).with_name(Path(cc).name.replace('gcc', 'ar').replace('clang', 'ar'))
        ar = str(candidate) if shutil.which(str(candidate)) else 'x86_64-w64-mingw32-ar'
    ar = ar or 'ar'
    flags = ['-std=c11', '-Wall', '-Wextra', '-Werror', '-pedantic', '-O2',
             '-ffp-contract=off', '-fno-fast-math', '-I', str(raiz / 'runtime/nativo/include')]
    if not windows:
        flags += ['-fPIC']
    if pruebas:
        flags += ['-DRT_TESTING']
    if sanitizers:
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-g']
    destino.mkdir(parents=True, exist_ok=True)
    fuentes = sorted((raiz / 'runtime/nativo/src').glob('*.c'))
    extension = '.dll' if windows else '.so'
    with tempfile.TemporaryDirectory(prefix='analizador-runtime-') as tmp:
        objetos = []
        for fuente in fuentes:
            obj = Path(tmp) / (fuente.stem + ('.obj' if windows else '.o'))
            ejecutar([cc, *flags, '-c', str(fuente), '-o', str(obj)])
            objetos.append(str(obj))
        ejecutar([ar, 'rcs', str(Path(tmp) / 'libanalizador_runtime.a'), *objetos])
        ejecutar([cc, *flags, '-shared', *objetos, '-lm', '-o', str(Path(tmp) / ('libanalizador_runtime' + extension))])
        nombres = ['libanalizador_runtime.a', 'libanalizador_runtime' + extension]
        if pruebas:
            probe = raiz / 'runtime/nativo/pruebas/probe.c'
            ejecutar([cc, *flags, '-shared', str(probe), *objetos, '-lm', '-o', str(Path(tmp) / ('libsonda_runtime' + extension))])
            fatal = 'sonda_fatal' + ('.exe' if windows else '')
            ejecutar([cc, *flags, str(raiz / 'runtime/nativo/pruebas/fatal.c'), *objetos, '-lm', '-o', str(Path(tmp) / fatal)])
            nombres += ['libsonda_runtime' + extension, fatal]
        identity = {'target': target, 'abi': 'Microsoft x64' if windows else 'System V AMD64',
                    'crt': 'UCRT' if windows else 'Linux', 'compiler_machine': machine, 'testing': pruebas}
        (Path(tmp) / 'identidad-runtime.json').write_text(json.dumps(identity, indent=2)+'\n')
        for nombre in nombres + ['identidad-runtime.json']:
            shutil.copy2(Path(tmp) / nombre, destino / nombre)
    return destino


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--salida', type=Path)
    p.add_argument('--target', choices=['linux-x64', 'windows-x64'], default='linux-x64')
    p.add_argument('--cc')
    p.add_argument('--ar')
    p.add_argument('--pruebas', action='store_true')
    args = p.parse_args()
    raiz = Path(__file__).resolve().parent.parent
    carpeta = raiz / ('build/runtime' if args.target == 'linux-x64' else 'build/nativo/windows-x64/runtime')
    try:
        destino = compilar(raiz, args.salida or carpeta, args.pruebas, target=args.target, cc=args.cc, ar=args.ar)
        print('Runtime nativo compilado [' + args.target + ']: ' + str(destino))
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise SystemExit('Error compilando el runtime: ' + str(e))


if __name__ == '__main__':
    main()
