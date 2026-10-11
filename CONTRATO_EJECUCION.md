# Contrato de ejecución, versión 1 (H0)

Fecha inicial: 7 de octubre de 2026. Ampliado: 10 de octubre de 2026.
Destinos contratados: **NASM/Linux x86-64/ELF64** y **NASM/Windows x64/PE-COFF**.

Los perfiles y `--target` se documentan en
[CONTRATO_MULTIPLATAFORMA.md](CONTRATO_MULTIPLATAFORMA.md). El núcleo semántico
conserva versión 1. H5 genera ejecutables Linux/Windows: Linux está verificado
nativamente y Windows bajo Wine, pendiente de aceptación en Windows. Ambas
proyecciones físicas derivan de la misma IR tipada y sus vistas didácticas.
Este contrato completa las decisiones operacionales de `GRAMATICA_CANONICA.md`
y `CONTRATO_SEMANTICO.md`. El núcleo H0 no añade sintaxis ni funciones de
entrada/salida. La biblioteca de consola se especificó posteriormente en
[ESPECIFICACION_RUNTIME_CONSOLA.md](ESPECIFICACION_RUNTIME_CONSOLA.md) (RC-1,
8 de octubre de 2026); el frontend y runtime de referencia ya la implementan
(H2/H3), y H4 aporta la biblioteca C nativa y sus puentes ABI.
Las reglas siguientes son decisiones de este proyecto que deberán cumplir
el intérprete de referencia y el backend nativo.

## Artefactos y ejecución

El compilador continúa implementado en Java 11+. Su salida final es un archivo
NASM `.asm`, ensamblable y enlazable con un runtime nativo suministrado por el
proyecto. El ejecutable se inicia desde consola y **no necesita JVM**. El runtime
podrá usar C, ensamblador y libc; no carga Java ni requiere otro intérprete.

Flujo H5: programa.asm incluye cuerpos, entrada, datos y puentes del módulo:

```sh
nasm -f elf64 programa.asm -o programa.o
gcc -no-pie programa.o build/runtime/libanalizador_runtime.a -lm -o programa
./programa
```

H4 entrega `build/runtime/libanalizador_runtime.a`; H5 genera el programa completo
y ensambla/enlaza por etapas en `tools/compilar_nativo.py`.
El perfil Linux actual usa System V AMD64 y enlace sin PIE. El perfil Windows
usa Microsoft x64 y runtime Win32/UCRT según el contrato ampliado. La proyección atómica ya fija offsets de campos/frames, alineación, paso de
argumentos escalares y slots virtuales en `IR_ATOMICA.md`. H4 implementa
strings, adaptadores y runtime nativo en `RUNTIME_NATIVO_H4.md`. No se sustituye NASM por
GNU as. Referencias de herramientas: [formato ELF de NASM](https://www.nasm.us/doc/nasm09.html#section-9.10),
[interfaz con C de 64 bits](https://www.nasm.us/doc/nasm13.html#section-13.3) y
[opciones de enlace de GCC](https://gcc.gnu.org/onlinedocs/gcc/Link-Options.html).

NASM local ya está disponible en `build/tools/nasm` y los puentes Linux H4
fueron ensamblados y enlazados en pruebas. H5 verifica también programas
completos; comandos y alcance en `GENERACION_NATIVA_H5.md`.

## Valores numéricos y booleanos

| Tipo | Regla |
|---|---|
| `int` | Entero con signo de 64 bits, complemento a dos: −9223372036854775808…9223372036854775807. |
| `float` | IEEE 754 binary64, redondeo al más próximo con empate al par. |
| `bool` | `false` o `true`; la representación numérica interna usa 0 o 1. |

Los literales enteros son decimales. Se rechazan los valores fuera de rango al
bajar el AST a IR, con la ubicación del literal. El caso `-9223372036854775808`
se reconoce como menos unario aplicado directamente al literal de magnitud
9223372036854775808. Esa magnitud positiva aislada se rechaza; no se la convierte
primero a un entero desbordado. El signo es un operador del lenguaje, no un
nuevo token. `ContratoEjecucion.literalEntero` acepta el texto ya combinado
con su signo para esta comprobación; el generador H2 deberá reconocer el AST.

La suma, resta, producto y negación de enteros aplican wrap módulo 2⁶⁴. La
división entera trunca hacia cero. `MIN / -1` produce `MIN` por wrap; el backend
debe tratar ese caso antes de `idiv`. Una división por cero es error de ejecución,
incluidos `0 / 0`, división flotante por `+0.0` y por `-0.0`.

Los literales flotantes deben producir un valor finito al redondearse a binary64;
el underflow a cero está permitido. El lexer conserva su formato decimal
vigente, sin exponentes ni sufijos. Las operaciones sí pueden producir infinitos,
NaN y ceros con signo. Se conserva el redondeo de cada operación, sin fusión de
producto y suma ni precisión extendida. La promoción `int → float` puede perder
precisión y redondea al par. NaN es distinto de todo valor, incluido él mismo;
las comparaciones de orden con NaN dan falso; `+0.0 == -0.0` da verdadero.

Ejemplos independientes para las pruebas:

| Expresión o valor | Resultado |
|---|---|
| 9223372036854775807 + 1 | −9223372036854775808 |
| −7 / 2 | −3 |
| −9223372036854775808 / −1 | −9223372036854775808 |
| 7.0 / 2.0 | 3.5 |
| 1.0 / 0.0 | `DIVISION_POR_CERO` |

El frontend valida los tipos; el generador IR implementado en H2 comprueba estos
rangos de literales y conserva la ubicación original del diagnóstico.

## Orden de evaluación y control

Se evalúan operandos de izquierda a derecha. En una llamada: receptor una vez,
argumentos de izquierda a derecha y después comprobación de receptor y llamada.
Un receptor implícito usa la instancia actual. En una indexación: lista una vez,
índice una vez y después comprobaciones de acceso.

Una asignación evalúa el destino completo, sus comprobaciones y después el lado
derecho. Una asignación compuesta calcula esa dirección y lee su valor una vez,
evalúa el lado derecho y escribe el resultado. Una variable, campo o elemento
no se vuelve a resolver por su nombre después del análisis semántico.

`&&` y `||` tienen cortocircuito: `false && f()` y `true || f()` no llaman a `f`.
Se bajan a saltos, no a una operación que evalúe ambos argumentos. `if` selecciona
una sola rama. `while (c)` y `repeat (c)` comprueban `c` **antes** de cada vuelta;
pueden ejecutar cero vueltas. Un retorno termina inmediatamente el método.

`repeat each (T x in lista)` evalúa la lista una vez y recorre índices de 0 a
longitud−1. Lee cada elemento al comenzar su vuelta; un cambio de un elemento
futuro mediante un alias se observa al visitarlo. La promoción admitida por el
semántico se aplica a la lectura del iterador, no a la lista completa. Reasignar
`x` no cambia el elemento de la lista. `Exist(lista)` evalúa la lista una vez y
ejecuta su bloque exactamente una vez si contiene al menos un elemento; una
lista vacía no ejecuta el bloque. Ambas construcciones fallan ante lista nula.

Los bucles infinitos de un programa válido siguen siendo un comportamiento
posible. Los límites del harness de pruebas no modifican el lenguaje.

## Referencias, cadenas y listas

`string`, `list<T>` y clases son referencias de 64 bits; `null` es la referencia
nula, representada por cero en el destino nativo. Asignar o pasar una referencia
preserva su identidad y sus alias, sin copiar el objeto o lista. Una promoción
de clase cambia el tipo estático y mantiene la misma referencia.

Las cadenas son inmutables, codificadas en UTF-8 y con longitud explícita en
bytes, sin depender de un terminador NUL. Los literales preservan su contenido;
el lenguaje no interpreta escapes de barra inversa. Los escapes del impresor
IR pertenecen exclusivamente al formato de exportación. La igualdad de cadenas
no nulas compara su contenido exacto, sin normalización Unicode. La igualdad de
objetos y listas compara identidad. Cualquier referencia solo es igual a `null`
si es nula; dos referencias nulas son iguales.

Cuando `+` es concatenación, primero se convierte cada valor a cadena:

| Valor | Texto |
|---|---|
| `int` | Decimal con signo, sin separadores ni ceros iniciales. |
| `float` | Formato de `Double.toString` de Java 11, independiente de locale: incluye `.0` para enteros flotantes, `-0.0`, `NaN`, `Infinity`, `-Infinity` y notación exponencial cuando corresponda. El runtime nativo deberá reproducir ese formato. |
| `bool` | `true` o `false`. |
| Cadena no nula | Su contenido. |
| Referencia nula | `null`, también si su tipo estático es `string` o lista. |
| Lista no nula | `[elemento, elemento]`; conversión recursiva y `[]` si está vacía. |
| Objeto no nulo | `<NombreDeClaseDinamica>`, sin dirección de memoria ni llamada a un método del usuario. |

La conversión de una cadena nula produce una cadena no nula con texto `null`.
El generador usará `A_STRING` también para operandos string que pueden ser nulos;
la operación abstracta de concatenación debe respetar la misma regla si recibe
referencias nulas. No se convierte `void` a cadena.

Las listas son mutables, de longitud fija y tipos invariantes. Los literales
crean listas nuevas y evalúan elementos de izquierda a derecha. Los literales
contextuales se bajan con sus conversiones individuales antes de `CREAR_LISTA`;
esta instrucción requiere tipos exactos. No existe redimensionamiento. Un acceso
requiere `0 ≤ índice < longitud`; primero se comprueba referencia nula y luego
rango. Listas vacías y listas nulas tienen comportamiento distinto.

## Objetos, métodos e inicialización

`new Clase()` crea una instancia nueva; no acepta argumentos ni invoca un método
del usuario llamado como la clase. Primero reserva toda la instancia con los
campos de la jerarquía y fija su clase dinámica. Inicializa **todos** los campos
a 0, +0.0, false o null según su tipo, antes de ejecutar cualquier inicializador.
Después ejecuta inicializadores de la base más antigua a la clase derivada, y
dentro de cada clase en orden de declaración. Un campo sin inicializador conserva
su valor predeterminado. Campos ocultos con igual nombre conservan identidades
y almacenamiento separados.

Las llamadas públicas de métodos del usuario son virtuales; las privadas usan destino directo de su
clase declaradora. El despacho virtual usa la clase dinámica definitiva incluso
dentro de un inicializador de la base. Así, una sobrescritura puede observar
campos derivados todavía con su valor predeterminado. Se conservan las firmas
resueltas por el semántico y los retornos covariantes de clase admitidos por él.

## Entrada, memoria y errores

La entrada es un `public main()` de retorno void y sin argumentos, **declarado**
en la clase elegida. Si hay una sola candidata en el proyecto se elige esa;
si hay varias, la herramienta solicitará el nombre de clase. No elige la primera
por orden de archivo. Una clase sin main propio no es una candidata por herencia.
El envoltorio nativo crea e inicializa su instancia y llama a su main. Al retornar,
libera recursos y devuelve estado 0 al sistema. No se añaden argumentos de consola
al método del lenguaje ni se lo transforma en estático.

La primera versión usa memoria dinámica administrada por el runtime del proceso:
registra asignaciones y libera todo al finalizar. No recoge objetos durante la
ejecución; ciclos que asignen indefinidamente pueden agotar memoria. Las cadenas
constantes pueden residir en almacenamiento estático. Locales y temporales usan
marcos por llamada, con recursión. Las direcciones de locales no escapan como
valores del lenguaje. El layout de objetos/listas/frames está en `IR_ATOMICA.md`;
H4 fija strings, constantes y runtime en `RUNTIME_NATIVO_H4.md` sin cambiar estas reglas.

Los fallos definidos (`DIVISION_POR_CERO`, `REFERENCIA_NULA`, `INDICE_FUERA_DE_RANGO`,
`MEMORIA_AGOTADA`) finalizan el ejecutable con estado 1 y diagnóstico en stderr
con código y ubicación original. Un fallo de reserva o de cálculo del tamaño de
una reserva se trata como memoria agotada antes de desbordar tamaños. El
agotamiento de pila queda sujeto al límite del sistema; no hay un tamaño de pila
ilimitado ni recuperación de ese fallo en esta versión.

Los accesos a campos/listas, longitudes y llamadas deben comprobar nulos; las
instrucciones explícitas de comprobación de la IR permiten anticipar el fallo,
pero no autorizan accesos inseguros si se omiten. Las comparaciones de referencia,
asignaciones y conversiones a texto permiten null según las reglas anteriores.

La generación requiere todas las unidades alcanzables válidas del coordinador
de proyecto, sin diagnósticos léxicos, sintácticos, semánticos o de imports. Los
imports se componen en un módulo; un import externo que solo sea metadato no
implica poder enlazar o llamar a código externo. Un símbolo sin implementación
disponible se diagnosticará explícitamente en H2/H3.

## Implementado y pendiente

H0 entrega este contrato, oráculos escalares en `ContratoEjecucion`, selección de
entrada y barrera de proyecto válido. H1 entrega el modelo/verificador/impresor
descritos en `IR_TIPADA.md`. H2/H3 implementan AST→IR, comprobación de rangos,
inicializadores y ejecución de todas las construcciones mediante los intérpretes
de referencia tipado y atómico (`IR_EJECUTABLE_H3.md`). El layout atómico ya
resuelve campos, marcos y slots; H4 implementa layout restante, memoria, runtime
C y puentes NASM/GNU as (`RUNTIME_NATIVO_H4.md`), con pruebas nativas.
H5 implementa cuerpos NASM, entrada, perfiles y builder completo; verificación
Linux nativa y Windows bajo Wine. Falta aceptación nativa Windows. H6 integra
las acciones de ASM/herramientas en UI; la visualización IR ya existe.

## Complemento de consola RC-1

Se definió una biblioteca integrada optativa, habilitada con
`import runtime.Consola;`, usada mediante un objeto y métodos de instancia.
Su API incluye imprimir/imprimirLinea y leerInt/leerFloat/leerBool/leerLinea.
No añade producciones BNF ni palabras reservadas; requiere registrar símbolos
intrínsecos, tipos, efectos y un despacho RUNTIME propio. Los métodos del usuario
mantienen sus reglas DIRECTO/VIRTUAL.

El detalle de EOF, UTF-8, rangos, línea de entrada, errores de I/O, helpers
C y oráculos está en [ESPECIFICACION_RUNTIME_CONSOLA.md](ESPECIFICACION_RUNTIME_CONSOLA.md).
Estos errores seguirán la finalización con estado 1 y origen de llamada de H0.
El contrato núcleo conserva versión 1; la ABI de consola tiene su propia versión 1.
El frontend y ambas IR implementan la biblioteca y sus llamadas. Los helpers
C y adaptadores nativos ya se implementan en H4, con 90/90 oráculos RC-1.
