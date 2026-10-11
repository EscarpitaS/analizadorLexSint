# Generación y ejecución de IR: H2

Fecha: 8 de octubre de 2026. H2 implementado para el subconjunto escalar.
Documento del tramo H2; H3 ya amplía este alcance a objetos y listas.
Estado actual en [IR_EJECUTABLE_H3.md](IR_EJECUTABLE_H3.md). La salida de este hito
se ejecuta con el intérprete Java; el ejecutable NASM sin JVM llegará en H4/H5.

La CLI exporta ahora cuádruplos atómicos con layout resuelto y ejecuta ese
código lineal. Detalle en [IR_ATOMICA.md](IR_ATOMICA.md). La IR tipada por bloques
se conserva para inspección con `--ir-tipada`.

Estado posterior: H4 ya implementa la biblioteca C y pasa 90/90 oráculos RC-1,
incluido SIGPIPE. Detalle en [RUNTIME_NATIVO_H4.md](RUNTIME_NATIVO_H4.md).
Las referencias a H4 pendiente en este documento describen el alcance de ese hito.

## Uso desde consola

Con JDK 11 o posterior, desde la raíz del proyecto:

```sh
python3 tools/compilar.py
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H2_control.txt --ir /tmp/control.ir
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H2_control.txt --ejecutar
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H2_consola.txt --ejecutar --ir /tmp/consola.ir
```

`JAVA_HOME` permite seleccionar el JDK para los scripts Python. En este equipo
el `java` del sistema sigue siendo Java 8; usa el binario del JDK 11+ seleccionado
al ejecutar el JAR. Ejemplo con el JDK disponible:

```sh
export JAVA_HOME=/home/sebastian/.jdks/ms-25.0.4.1
"$JAVA_HOME/bin/java" -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H2_control.txt --ejecutar
```

Sin `--ejecutar`, se exporta IR a stdout o al archivo de `--ir`. Con ejecución,
stdout pertenece exclusivamente al programa. `--entrada Clase` selecciona un
main público declarado si hay varias candidatas. Los imports locales se validan;
la CLI no abre diálogos gráficos para dependencias ausentes. Una exportación
no puede sobrescribir una unidad fuente, tampoco mediante un enlace simbólico.

Los errores de compilación/ejecución terminan con estado 1. El diagnóstico de
runtime contiene código estable, archivo escapado, línea y columna conforme
RC-1. `--max-pasos N` activa un presupuesto opcional del harness; al alcanzarlo
sale con estado 2 (`LIMITE_DEL_HARNESS`), separado de los errores del lenguaje.
Cero, valor predeterminado, no impone un límite. Un programa válido puede tener
un ciclo infinito; el presupuesto sirve para pruebas, no altera H0.

## Alcance implementado

| Construcción | Alcance histórico de H2 |
|---|---|
| Literales int/float/bool/string/null | Sí; int64, float64 y rangos de literales comprobados al generar. |
| Locales, var/let, parámetros y asignaciones | Sí; identidad del símbolo y almacenamiento mutable separado de temporales. |
| Asignaciones compuestas | Sí para locales; evalúan destino/lectura/argumento una sola vez. |
| Aritmética, comparación, not, menos unario | Sí; promociones explícitas, wrap int64 y división por cero con origen. |
| if/else/else if, while, repeat (condición), return | Sí; bloques verificables con terminadores y uniones mediante ranuras. |
| && y \|\| | Sí; saltos con cortocircuito, incluso dentro de ciclos y expresiones anidadas. |
| Métodos públicos/privados, sobrecargas y recursión | Sí en clases sin campos/herencia. El receptor de entrada es una instancia vacía. |
| Imports locales | Sí; módulo conjunto, generación bloqueada por cualquier unidad inválida o ausente. |
| Consola integrada | Sí; import exacto, tipo sellado, creación, identidad, despacho RUNTIME y las seis operaciones RC-1. |
| Cadenas básicas, concatenación y conversión escalar a texto | Sí; Unicode/NUL, igualdad por contenido, null→"null" y formato Java 11. |
| Campos, inicializadores, herencia y new de clases del usuario | H3 pendiente; diagnóstico explícito. |
| Listas, acceso por índice, repeat each y Exist | H3 pendiente; diagnóstico explícito. |
| Despacho virtual con herencia y formateo de listas/objetos completos | H3 pendiente. H2 conserva LLAMAR_VIRTUAL para públicos sin jerarquía. |
| Helpers C, SIGPIPE/EPIPE, layout restante y ensamblador NASM | H4/H5 pendientes; offsets/ABI ya resueltos. |
| Botones/vistas de generación en la interfaz | H6 pendiente. |

`GeneradorIr` consume los resultados finales de `Main.analizarProyecto`, recorre
el AST decorado y usa símbolos, firmas, receptores y conversiones ya resueltos.
En H2 se rechazaban también construcciones pendientes en ramas no tomadas y
métodos no invocados. H3 elimina esas barreras e implementa esas construcciones. Un error devuelve diagnósticos y **ningún
módulo parcial**. La IR tipada pasa `VerificadorIr`; la proyección física pasa `VerificadorAtomicoIr`
antes de exportar o ejecutar.

`InterpreteIr` mantiene marcos en una pila explícita; una llamada recursiva no
consume la pila Java. Los canales son inyectables y compartidos por todas las
instancias de Consola de la ejecución. No se cierran los canales del llamador.
Las representaciones Java son de referencia y no fijan el layout nativo.

## Consola y formato de float

`ConsolaReferencia` valida líneas UTF-8 completas, con decoder en modo REPORT:
rechaza overlong, sustitutos, secuencias truncadas y valores superiores a
U+10FFFF. Normaliza únicamente CRLF, admite NUL, limita contenido a 1 MiB y
conserva EOF. Las lecturas escalares validan toda la gramática antes del rango;
rechazan prefijos, hexadecimales, NaN/Inf, comas, sufijos y espacios Unicode.
El underflow float conserva cero con signo. Toda impresión vacía stdout.

Para reproducir **Java 11** incluso al ejecutar con un JDK reciente, se incluyeron
`FloatingDecimal`, `FDBigInteger`, `DoubleConsts` y `FloatConsts` del tag oficial
[OpenJDK jdk-11+28](https://github.com/openjdk/jdk11u/tree/jdk-11%2B28/src/java.base/share/classes/jdk/internal/math).
La única adaptación es el nombre de paquete. No se delega el formato al
`Double.toString` del JDK anfitrión. Se conservan encabezados, licencias completas
y hashes de procedencia en `runtime/terceros/openjdk11/`. Esta implementación
Java sirve de referencia; la alternativa nativa debe satisfacer los mismos
oráculos cuando se implemente.

## Verificación

```sh
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasGeneracionIr
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
python3 tools/probar_consola.py
```

La suite Java comprueba resultados y efectos de fuente→IR→ejecución: rutas,
conversiones, recursión profunda, evaluación única, cortocircuito, imports,
tipos de intrínsecos y ubicación de fallos. Está integrada en la
regresión. Los oráculos JSON originales se ejecutan mediante un harness binario
que comprueba bytes exactos, retornos, códigos, posición del fallo y bits de cero
con signo, además de fallos de lectura, escritura parcial y flush.

**89/90 oráculos RC-1 pasan actualmente en el adaptador Java.** H3 añadió
impresión de listas/objetos y reserva fallida simulada (`MEMORIA_AGOTADA`).
Pipe roto real (SIGPIPE/EPIPE nativo, H4) aparece explícitamente como pendiente. El harness entrega entrada byte a byte, cubriendo también secuencias UTF-8
fragmentadas. Esta cifra no certifica los helpers nativos ni el runtime completo.
EOF, errores de entrada y salida parcial no se sustituyen por valores de éxito.
