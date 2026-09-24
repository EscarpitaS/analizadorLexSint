# Analizador lexico y sintactico

Proyecto de automatas con:

- Analizador lexico por matriz de transiciones.
- Analizador sintactico con clase `Gramatica`.
- Árbol sintáctico con acciones y árbol semántico con tipos anotados.
- Diagnósticos sintácticos y semánticos combinados; análisis parcial tras recuperación.
- Tabla de simbolos con identificadores encontrados.
- Interfaz Swing para abrir archivos, ver tokens, errores, codigo, arbol, gramatica y reporte.

La especificación vigente del lenguaje está en [GRAMATICA_CANONICA.md](GRAMATICA_CANONICA.md).
El contrato del motor está en [CONTRATO_SEMANTICO.md](CONTRATO_SEMANTICO.md).
La documentación histórica se conserva solo como antecedente.

## Uso rapido

1. Ejecuta `Main`.
2. Usa `Abrir ejemplo` para cargar `untitled/ejemplos/programa_demo.txt`.
3. Usa `Abrir archivo` para analizar otro archivo `.txt`, `.java` o `.cpp`.
4. Activa `Validar imports locales` solo si los imports son archivos del mismo proyecto.
5. Usa `Guardar reporte` para exportar el resultado.

En IntelliJ, ejecuta la clase `Main` sin argumentos para abrir Swing. Si la
salida muestra que la JVM no tiene entorno gráfico, elimina
`-Djava.awt.headless=true` de la configuración de ejecución; ese parámetro
fuerza el modo consola y evita que Java cree ventanas.

## Verificación

Compila las fuentes y ejecuta `PruebasRegresion`. La prueba cubre programas
válidos, errores léxicos, sintácticos y semánticos del lenguaje canónico.
Incluye `PruebasSemanticas`, que también puede ejecutarse por separado.
Se necesita JDK 11 o posterior (el proyecto usa `Files.readString`).

## Ejemplos

- `untitled/ejemplos/programa_demo.txt`: programa valido para mostrar tokens, arbol y tabla de simbolos.
- `untitled/ejemplos/programa_error_sintactico.txt`: programa con error sintactico para demostrar el parser.
- `untitled/ejemplos/programa_con_errores.txt`: programa con errores lexicos para demostrar diagnosticos del automata.

## Conformidad con la gramática

`PruebasRegresion` incluye `PruebasConformidadGramatica`. Para comprobar también
que el documento y las producciones ejecutables no divergen, ejecuta desde
esta carpeta:

```sh
python3 tools/verificar_gramatica.py
```

Los hallazgos y el alcance de la revisión están en
[AUDITORIA_GRAMATICA.md](AUDITORIA_GRAMATICA.md).
