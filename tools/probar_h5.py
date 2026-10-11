#!/usr/bin/env python3
"""Compara programas NASM completos con oráculos independientes y ambos intérpretes."""
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
from compilar_nativo import construir, herramienta_java, proceso


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--optimizar',action='store_true',help='Corpus y backend H7, con IR original conservada')
    p.add_argument('--target',choices=['linux-x64','windows-x64'],default='linux-x64')
    p.add_argument('--jar',type=Path)
    p.add_argument('--nasm')
    p.add_argument('--cc')
    p.add_argument('--ar')
    p.add_argument('--salida',type=Path)
    p.add_argument('--wine-prefix',type=Path,help='Perfil Wine aislado ya preparado, o carpeta nueva')
    p.add_argument('--wine',action='store_true',help='Validación adicional en Wine; no acredita aceptación nativa Windows')
    args=p.parse_args();raiz=Path(__file__).resolve().parent.parent;windows=args.target=='windows-x64'
    if windows and platform.system()!='Windows' and not args.wine:
        raise SystemExit('Para ejecutar Windows aquí necesitas --wine; en Windows ejecútalo sin esa opción.')
    if args.wine and not windows: raise SystemExit('--wine solo corresponde a windows-x64')
    salida=args.salida or raiz/('build/verificacion-h7' if args.optimizar else 'build/verificacion-h5')/args.target
    if salida.exists(): raise SystemExit('Elige una carpeta de pruebas nueva: '+str(salida))
    salida.mkdir(parents=True);jar=args.jar or raiz/'build/AnalizadorLexSint.jar'
    cc=args.cc or os.environ.get('CC_WINDOWS' if windows else 'CC',
                'gcc' if not windows or platform.system()=='Windows' else 'x86_64-w64-mingw32-gcc')
    nasm=args.nasm or os.environ.get('NASM') or shutil.which('nasm') or str(raiz/'build/tools/nasm')
    corpus=salida/'corpus';corpus.mkdir()
    ref=proceso([herramienta_java(),'-Djava.awt.headless=true','-cp',str(jar),'PruebasH7' if args.optimizar else 'PruebasH5',str(corpus),args.target], 'Oráculos IR')
    print(ref.stdout.decode(),end='',flush=True)
    compilar(raiz,salida/'runtime',target=args.target,cc=cc,ar=args.ar)
    report=[]
    wine_prefix=args.wine_prefix or salida/'wine'
    if args.wine: wine_prefix.mkdir(parents=True,exist_ok=True)
    for asm in sorted(corpus.glob('*.asm')):
        obj=asm.with_suffix('.obj' if windows else '.o');exe=asm.with_suffix('.exe' if windows else '.bin')
        proceso([nasm,'-f','win64' if windows else 'elf64',str(asm),'-o',str(obj)],'NASM '+asm.stem)
        proceso([cc,*([] if windows else ['-no-pie']),str(obj),str(salida/'runtime/libanalizador_runtime.a'),'-lm','-o',str(exe)],'Enlace '+asm.stem)
        env=dict(os.environ)
        if args.wine:
            env.update(WINEPREFIX=str(wine_prefix.resolve()),WINEDEBUG='-all')
            comando=['wine',str(exe.resolve())]
        else: comando=[str(exe.resolve())]
        result=subprocess.run(comando,input=asm.with_suffix('.stdin').read_bytes(),capture_output=True,timeout=55,env=env)
        expected=(int(asm.with_suffix('.estado').read_text()),asm.with_suffix('.stdout').read_bytes(),asm.with_suffix('.stderr').read_bytes())
        actual=(result.returncode,result.stdout,result.stderr)
        asm.with_suffix('.stdout.real').write_bytes(result.stdout);asm.with_suffix('.stderr.real').write_bytes(result.stderr)
        if actual!=expected: raise AssertionError(asm.stem+': esperado '+repr(expected)+'; obtenido '+repr(actual))
        report.append({'programa':asm.stem,'target':args.target,'estado':result.returncode,'bytes_stdout':len(result.stdout),
                       'bytes_stderr':len(result.stderr),'entorno':'Wine' if args.wine else platform.system()})
        print('OK: '+asm.stem,flush=True)
    rutas_windows = 0
    if windows:
        # Adaptador físico: pipes rotos de stdout/stderr y finalización Win32 real.
        compilar(raiz,salida/'runtime-pruebas',pruebas=True,target=args.target,cc=cc,ar=args.ar)
        from probar_runtime_nativo import fatal_cases
        rutas_windows=fatal_cases((salida/'runtime-pruebas/sonda_fatal.exe').resolve(),
                                 prefix=['wine'] if args.wine else [],env=env,timeout=55)
        selftest=salida/'selftest.exe'
        proceso([cc,'-std=c11','-Wall','-Wextra','-Werror','-pedantic','-DRT_TESTING',
                 '-I',str(raiz/'runtime/nativo/include'),str(raiz/'runtime/nativo/pruebas/selftest.c'),
                 str(salida/'runtime-pruebas/libanalizador_runtime.a'),'-lm','-o',str(selftest)],'Layout C Windows')
        result=subprocess.run((['wine'] if args.wine else [])+[str(selftest.resolve())],capture_output=True,timeout=55,env=env)
        assert result.returncode==0 and not result.stderr,(result.returncode,result.stdout,result.stderr)
        print('OK: '+str(rutas_windows)+' rutas de finalización Windows y layout/memoria C',flush=True)
    # Ejercitar el flujo público, publicación completa y rechazo sin artefactos.
    carpeta=salida/'cli-programa'
    construir(raiz,corpus/'escalares.txt',carpeta,target=args.target,entrada='Main',jar=jar,nasm=nasm,cc=cc,ar=args.ar,optimizar=args.optimizar)
    assert (carpeta/'identidad-programa.json').is_file()
    try: construir(raiz,corpus/'escalares.txt',carpeta,target=args.target,entrada='Main',jar=jar,nasm=nasm,cc=cc,ar=args.ar,optimizar=args.optimizar)
    except RuntimeError: pass
    else: raise AssertionError('Debe rechazar sobreescritura de carpeta')
    mala=salida/'invalida.txt';mala.write_text('{ class Main { public main() { int x=; } } }')
    try: construir(raiz,mala,salida/'no-publicar',target=args.target,entrada='Main',jar=jar,nasm=nasm,cc=cc,ar=args.ar,optimizar=args.optimizar)
    except RuntimeError: assert not (salida/'no-publicar').exists()
    else: raise AssertionError('Una fuente inválida no debe publicar artefactos')
    contenido=(corpus/'escalares.txt').read_bytes()
    guard=subprocess.run([herramienta_java(),'-cp',str(jar),'CompilarIr',str(corpus/'escalares.txt'),
                          '--entrada','Main','--target',args.target,'--asm',str(corpus/'escalares.txt')],capture_output=True,timeout=30)
    assert guard.returncode==1 and b'sobrescribir' in guard.stderr and (corpus/'escalares.txt').read_bytes()==contenido
    ignorado=salida/'destino-invalido.asm'
    unknown=subprocess.run([herramienta_java(),'-cp',str(jar),'CompilarIr',str(corpus/'escalares.txt'),'--target','inexistente','--asm',str(ignorado)],capture_output=True,timeout=30)
    assert unknown.returncode==1 and not ignorado.exists()
    fail_dir=salida/'enlace-fallido'
    try: construir(raiz,corpus/'escalares.txt',fail_dir,target=args.target,entrada='Main',jar=jar,nasm=nasm,cc=str(salida/'cc-inexistente'),ar=args.ar)
    except RuntimeError: assert not fail_dir.exists()
    else: raise AssertionError('No publica un enlace fallido')
    (salida/'resultado.json').write_text(json.dumps({'programas':report,'target':args.target,'ejecucion_nativa':not args.wine,
        'optimizacion':'H7' if args.optimizar else 'ninguna','checks_cli':6,'rutas_runtime_windows':rutas_windows},indent=2)+'\n')
    print('OK: '+str(len(report))+' ejecutables '+args.target+'; stdout, stderr y estados coinciden. Informe: '+str(salida/'resultado.json'))


if __name__=='__main__':
    try: main()
    except (AssertionError,RuntimeError,OSError,subprocess.SubprocessError) as e:
        print('Fallo H5: '+str(e),file=sys.stderr);sys.exit(1)
