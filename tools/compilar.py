#!/usr/bin/env python3
"""Compila todas las subcarpetas de src y genera el JAR ejecutable (JDK 11+)."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    raiz = Path(__file__).resolve().parent.parent
    java_home = os.environ.get('JAVA_HOME')
    extension = '.exe' if os.name == 'nt' else ''

    def herramienta(nombre):
        ruta = str(Path(java_home) / 'bin' / (nombre + extension)) if java_home else shutil.which(nombre)
        if not ruta or not Path(ruta).is_file():
            raise SystemExit('No se encontró ' + nombre + '. Configura JAVA_HOME con un JDK 11 o posterior.')
        return ruta

    javac = herramienta('javac')
    jar = herramienta('jar')
    destino = raiz / 'build' / 'AnalizadorLexSint.jar'
    destino.parent.mkdir(exist_ok=True)
    fuentes = sorted((raiz / 'untitled' / 'src').rglob('*.java'))
    with tempfile.TemporaryDirectory(prefix='analizadorLexSint-build-') as temporal:
        clases = Path(temporal) / 'clases'
        clases.mkdir()
        subprocess.run([javac, '--release', '11', '-encoding', 'UTF-8', '-d', str(clases)]
                       + [str(p) for p in fuentes], check=True, cwd=raiz)
        jar_temporal = Path(temporal) / destino.name
        comando = [jar, '--create', '--file', str(jar_temporal), '--main-class', 'Main',
                   '-C', str(clases), '.', '-C', str(raiz / 'untitled'), 'ejemplos',
                   '-C', str(raiz / 'untitled' / 'src'), 'runtime/jdk11']
        for nombre in ['README_ENTREGA.md', 'GRAMATICA_CANONICA.md', 'CONTRATO_SEMANTICO.md',
                       'PRUEBAS_COMPOSICION.md', 'PRUEBAS_RECUPERACION.md',
                       'CONTRATO_EJECUCION.md', 'CONTRATO_MULTIPLATAFORMA.md', 'IR_DIDACTICA.md', 'IR_TIPADA.md', 'AUDITORIA_IR.md', 'ROADMAP_GENERACION_CODIGO.md',
                       'ESPECIFICACION_RUNTIME_CONSOLA.md', 'IR_EJECUTABLE_H2.md', 'IR_EJECUTABLE_H3.md', 'IR_ATOMICA.md', 'RUNTIME_NATIVO_H4.md', 'GENERACION_NATIVA_H5.md', 'INTEGRACION_NATIVA_H6.md', 'OPTIMIZACION_H7.md',
                       'runtime/terceros/openjdk11', 'runtime/nativo',
                       'runtime/especificacion/consola_v1.json', 'runtime/especificacion/float_java11_v1.json']:
            comando.extend(['-C', str(raiz), nombre])
        subprocess.run(comando, check=True, cwd=raiz)
        shutil.copy2(jar_temporal, destino)
    print('JAR generado: ' + str(destino))


if __name__ == '__main__':
    main()
