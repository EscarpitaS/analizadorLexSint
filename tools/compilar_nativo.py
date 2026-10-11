#!/usr/bin/env python3
"""H5/H7: fuente → NASM → objeto → ejecutable, con etapas y destinos explícitos."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
from compilar_runtime import compilar


def herramienta_java():
    home = os.environ.get('JAVA_HOME')
    return str(Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if home else 'java'


def proceso(comando, etapa, plazo=120):
    try:
        result = subprocess.run(comando, capture_output=True, timeout=plazo)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise RuntimeError(etapa + ': ' + str(e)) from e
    if result.returncode:
        raise RuntimeError(etapa + ' (estado ' + str(result.returncode) + '):\n' +
                           (result.stdout + result.stderr).decode('utf-8', errors='replace'))
    return result


def construir(raiz, fuente, carpeta, *, target='linux-x64', entrada=None, etapa='ejecutable',
              jar=None, nasm=None, cc=None, ar=None, optimizar=False):
    if target not in ('linux-x64', 'windows-x64') or etapa not in ('asm', 'objeto', 'ejecutable'):
        raise RuntimeError('Perfil o etapa desconocidos')
    fuente=fuente.resolve(); carpeta=carpeta.resolve()
    jar=jar or raiz/'build/AnalizadorLexSint.jar'
    if not jar.is_file():
        raise RuntimeError('No existe el JAR. Ejecuta primero tools/compilar.py.')
    windows=target=='windows-x64'
    nombres=['programa.asm','programa.ir','programa.tipada.ir','programa.obj' if windows else 'programa.o',
             'programa.exe' if windows else 'programa','identidad-programa.json']
    # No reemplazar fuentes ni carpetas previas: cada construcción se publica completa.
    if carpeta.exists():
        raise RuntimeError('La carpeta de salida ya existe. Elige una carpeta nueva: '+str(carpeta))
    carpeta.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.h5-',dir=carpeta.parent) as tmp:
        stage=Path(tmp); asm=stage/nombres[0]; obj=stage/nombres[3]; exe=stage/nombres[4]
        comando=[herramienta_java(),'-Djava.awt.headless=true','-cp',str(jar),'CompilarIr',str(fuente),
                 '--target',target,'--asm',str(asm),'--ir',str(stage/nombres[1]),'--ir-tipada',str(stage/nombres[2])]
        if entrada: comando+=['--entrada',entrada]
        if optimizar: comando+=['--optimizar','--ir-original',str(stage/'programa.original.ir'),'--ir-tipada-original',str(stage/'programa.original.tipada.ir')]
        proceso(comando,'Generación IR/NASM')
        if etapa!='asm':
            nasm=nasm or os.environ.get('NASM') or shutil.which('nasm')
            local=raiz/'build/tools/nasm'
            if not nasm and local.is_file(): nasm=str(local)
            if not nasm: raise RuntimeError('Ensamblado: no se encontró NASM. Configura NASM o --nasm.')
            proceso([nasm,'-f','win64' if windows else 'elf64',str(asm),'-o',str(obj)],'Ensamblado NASM')
        if etapa=='ejecutable':
            cc=cc or os.environ.get('CC_WINDOWS' if windows else 'CC',
                'gcc' if not windows or platform.system()=='Windows' else 'x86_64-w64-mingw32-gcc')
            try:
                compilar(raiz,stage/'runtime',target=target,cc=cc,ar=ar)
            except (SystemExit,OSError,subprocess.SubprocessError) as e:
                raise RuntimeError('Runtime '+target+': '+str(e)) from e
            identidad=json.loads((stage/'runtime/identidad-runtime.json').read_text())
            if identidad['target']!=target or identidad['testing']:
                raise RuntimeError('Runtime incompatible con el destino de enlace')
            proceso([cc,*([] if windows else ['-no-pie']),str(obj),str(stage/'runtime/libanalizador_runtime.a'),
                     '-lm','-o',str(exe)],'Enlace '+target)
        (stage/nombres[5]).write_text(json.dumps({'target':target,'etapa':etapa,'fuente':str(fuente),
             'optimizacion':'H7' if optimizar else 'ninguna','ejecucion_verificada':False,'jvm_al_ejecutar':False},indent=2)+'\n',encoding='utf-8')
        os.rename(stage,carpeta)
    return carpeta


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('fuente',type=Path)
    p.add_argument('--entrada')
    p.add_argument('--optimizar',action='store_true')
    p.add_argument('--target',choices=['linux-x64','windows-x64'],default='linux-x64')
    p.add_argument('--etapa',choices=['asm','objeto','ejecutable'],default='ejecutable')
    p.add_argument('--salida',type=Path)
    p.add_argument('--jar',type=Path)
    p.add_argument('--nasm')
    p.add_argument('--cc')
    p.add_argument('--ar')
    args=p.parse_args(); raiz=Path(__file__).resolve().parent.parent
    salida=args.salida or raiz/'build/nativo'/args.target/args.fuente.stem
    try:
        carpeta=construir(raiz,args.fuente,salida,target=args.target,entrada=args.entrada,etapa=args.etapa,
                         jar=args.jar,nasm=args.nasm,cc=args.cc,ar=args.ar,optimizar=args.optimizar)
    except (RuntimeError,OSError) as e:
        print(str(e),file=sys.stderr);return 1
    print(('H7' if args.optimizar else 'H5')+' ['+args.target+'] etapa '+args.etapa+': '+str(carpeta));return 0


if __name__=='__main__':
    sys.exit(main())
