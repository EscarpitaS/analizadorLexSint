# H7: optimización verificable y opcional

Implementación del 11 de octubre de 2026. La primera versión H7 incluye
propagación/plegado, eliminación de cálculos muertos, simplificación de CFG y
asignación local de registros. No cambia las clases del modelo IR ni sus
operaciones, y conserva la atomicidad de los cuádruplos. Se activa explícitamente;
sin activarla se conserva la generación H5/H6 de referencia.

## Flujo y comparación didáctica

```text
IR tipada original, verificada
    → OptimizadorIr → IR tipada optimizada, verificada
    → LinealizadorIr por destino → cuádruplos atómicos y layout nuevo, verificados
    → GeneradorEnsamblador(true) → NASM con asignación local de registros
```

Las IR originales son inmutables y permanecen disponibles. Se crean módulos,
funciones y bloques nuevos; firmas, símbolos, clases, fuentes, orden de efectos
y coordenadas de las operaciones conservadas permanecen iguales. Las tablas de
métodos siguen incluyendo todas las funciones, aunque un cuerpo tenga menos
instrucciones. El layout se recalcula desde la IR optimizada antes del backend.

En **Resultados → ASM y ejecutable**, activa **Optimizar H7**. Cambiar esta opción
cancela el trabajador anterior, invalida sus artefactos y regenera desde la misma
instantánea. **Comparar optimización** ofrece IR tipada original/usada, cuádruplos
físicos originales, tabla didáctica de la IR usada y resumen de instrucciones,
bloques, cuádruplos, temporales en registros y suma de marcos reservados. La vista
**IR física del destino** muestra los cuádruplos usados y **ASM completo** su
ensamblador. Las vistas anteriores de **IR del proyecto** conservan el original.

Las exportaciones y construcciones corresponden a la opción seleccionada.
El builder gráfico añade, al optimizar, `programa.original.ir`,
`programa.original.tipada.ir` y `optimizacion.txt`. La identidad registra
`optimizacion=H7` o `ninguna`, además del destino, entrada y hashes de fuentes/ASM.
Las copias originales permiten comparar exactamente el mismo proyecto y entrada.

## Transformaciones sobre IR tipada

`OptimizadorIr` rechaza primero una IR inválida y verifica el resultado completo.
Aplica los pases hasta alcanzar un punto fijo determinista:

1. **Copias y constantes.** Sustituye temporales inmutables por valores equivalentes
   del mismo tipo. Pliega operaciones aritméticas, negación, booleanos, comparaciones
   escalares e `int → float` cuando todos sus operandos son constantes. Las cadenas,
   objetos y listas no se reemplazan por aproximaciones de su contenido.
2. **Lecturas locales demostradas.** Dentro de un bloque recuerda constantes
   guardadas en locales y reemplaza lecturas posteriores de esa misma dirección.
   No supone un valor inicial ni propaga memoria entre bloques. Las llamadas y
   escrituras a direcciones no reconocidas invalidan esos hechos. No se adelantan
   lecturas de campos/listas ni se duplican receptores, índices o llamadas.
3. **Control.** Una condición constante elige su arista. Elimina bloques que ya no
   son alcanzables; redirige saltos por bloques vacíos y fusiona bloques unidos
   por una arista con un solo predecesor. Conserva los ciclos de saltos vacíos.
4. **Cálculos muertos.** Conserva raíces con efectos/errores y recorre sus operandos,
   junto con condiciones y retornos. Elimina definiciones puras que no alimentan
   ninguna raíz. No elimina llamadas, stores, asignaciones de memoria, conversiones
   a string, concatenaciones, lecturas potencialmente inválidas ni comprobaciones.

Los enteros siguen siendo `int64` con wrap. El plegado de división usa el helper
H0 para `MIN_INT/-1`; divisor cero queda como división con su diagnóstico original.
Los flotantes se calculan con semántica `strictfp` y precisión binary64. Se conservan
`-0.0`, infinidades y las comparaciones IEEE con NaN. No se reasocian sumas/productos,
no se aplica `x * 0`, `x + 0` o `x == x` a flotantes, y no se cambia una división
por multiplicación inversa. La división por `+0.0` o `-0.0` no se pliega.

La eliminación de operaciones con posibles errores requiere una prueba concreta.
Por ejemplo, `1/0` sin uso sigue fallando; crear una lista/objeto sin usarlo sigue
reservando memoria y puede fallar. Un bloque descartado por una condición constante
no se ejecutaba en el original, por lo que sus efectos no se trasladan al camino vivo.
Las conversiones a string se mantienen para no eliminar asignaciones de memoria.

## Registros y emisión NASM

`AsignadorRegistrosNasm` calcula intervalos de temporales por segmento lineal y
asigna hasta dos registros, **R8/R9**, volátiles en Linux y Windows. Un temporal
solo puede residir en registro si su definición y todos sus usos están dentro
del mismo segmento. Etiquetas, saltos, lectura/paso de parámetros, llamadas,
retornos y preparación del retorno cortan segmentos. Los valores que cruzan una
barrera conservan su ranura de pila; no hay caché de memoria mutable.

Los intervalos se asignan en orden determinista. Si ambos registros están ocupados,
el temporal usa la pila. Los flotantes conservan sus bits con `movq` entre GPR y
XMM; la aritmética sigue usando SSE2. Los operandos se cargan en scratch antes de
guardar el resultado, permitiendo reutilizar el registro de un último uso.

No cambian los registros de argumentos, las reglas de preservación, shadow space,
alineación de frames, sondeo de pila Windows ni metadatos de desenrollado. Un destino
indirecto que atraviesa PARAM sigue en la pila y se carga en **R11** al llamar. Las
ranuras de la IR física permanecen asignadas incluso si un temporal usa registro;
esta versión reduce accesos a memoria, sin comprimir adicionalmente el frame.
El emisor también omite un `jmp` cuyo siguiente cuádruplo es la etiqueta destino;
la IR física mantiene su salto explícito para facilitar la lectura.

## CLI

```sh
java -cp build/AnalizadorLexSint.jar CompilarIr programa.txt --entrada Main \
  --target linux-x64 --optimizar --asm programa.asm --ir programa.ir \
  --ir-tipada programa.tipada.ir --ir-original programa.original.ir \
  --ir-tipada-original programa.original.tipada.ir

python3 tools/compilar_nativo.py programa.txt --entrada Main --optimizar \
  --target linux-x64 --salida build/nativo/h7-programa
```

Las nuevas salidas tienen las mismas protecciones contra sobrescribir fuentes o
usar dos rutas que designen un mismo archivo. `--optimizar` también se aplica a
`--ejecutar` del intérprete; sus límites de instrucciones son del harness, no del
lenguaje, y pueden alcanzarse en distinto punto al reducir instrucciones.

## Pruebas y aceptación

`PruebasH7` se integra en la regresión e incluye oráculos independientes, comparación
entre IR original/optimizada y ambos intérpretes por perfil, determinismo,
idempotencia, memoria acotada, conservación de origen de errores, ciclos infinitos,
rechazo de IR inválida, comparación gráfica y 200 casos de overflow aleatorios.
El corpus nativo combina los 26 programas H5 con 11 adicionales H7: enteros extremos,
ceros flotantes con signo, control constante/dinámico, presión de registros,
float leído de consola, alias y errores de valores sin uso.

```sh
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
JAVA_HOME=/ruta/jdk python3 tools/probar_h5.py --optimizar --target linux-x64 \
  --nasm /ruta/nasm --salida build/verificacion-h7/linux-x64
# Adicionalmente: --target windows-x64 --cc /ruta/cc-ucrt --ar /ruta/ar
# Desde Linux puede usarse --wine con un --wine-prefix aislado.
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasH6Nativas \
  /ruta/nasm gcc ar --optimizar
```

Validación realizada: regresión completa, 593 comprobaciones diferenciales H7
sobre el corpus ampliado y 37 ejecutables Linux, más los mismos 37 ejecutables
Windows bajo Wine y 11 rutas de error del runtime Windows. Un JAR aislado pasa
26 comprobaciones del builder gráfico con optimización, originales e identidad.
Las salidas, stderr y estados nativos coinciden byte por byte con los oráculos. Wine acredita comprobaciones adicionales de la ABI Windows, pero la
aceptación en Windows real continúa pendiente, junto con los diálogos manuales GUI.

SSA no se introduce: los temporales ya tienen definición única y dominancia
verificada; estas optimizaciones no la necesitan. Análisis global de memoria,
SCCP completo, eliminación de stores, expresiones comunes, asignación global de
registros y compresión adicional de frames quedan fuera de esta primera versión.
H7 implementa optimizaciones conservadoras; no promete una IR mínima ni un aumento
de velocidad en todos los programas.
