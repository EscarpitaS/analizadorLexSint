# Runtime de consola: especificación RC-1

Fecha inicial: 8 de octubre de 2026. Ampliado: 10 de octubre de 2026.
RC-1 es común a Linux x64 y Windows x64. El runtime C H4 implementado y sus
90/90 oráculos corresponden a Linux. El port Windows está especificado y
pendiente en [CONTRATO_MULTIPLATAFORMA.md](CONTRATO_MULTIPLATAFORMA.md), incluidos
consola Unicode, redirecciones, EOF y equivalente de pipe roto.

**Estado: RC-1 implementado en referencia H2/H3 y runtime nativo H4.** El frontend reconoce
`import runtime.Consola` y los intérpretes Java ejecutan impresión/lectura,
incluidos listas y objetos. Pasan 89/90 oráculos Java y 90/90 nativos, incluido
pipe roto real. Alcance en [IR_EJECUTABLE_H3.md](IR_EJECUTABLE_H3.md) y
[RUNTIME_NATIVO_H4.md](RUNTIME_NATIVO_H4.md). La biblioteca C ya es enlazable;
el backend completo del programa corresponde a H5.

## 1. API del lenguaje

La biblioteca se habilitará con el import exacto `runtime.Consola`:

```text
import runtime.Consola;
{
    class Programa {
        public main() {
            Consola consola = new Consola();
            consola.imprimir("Nombre: ");
            string nombre = consola.leerLinea();
            if (nombre != null) {
                consola.imprimir("Edad: ");
                int edad = consola.leerInt();
                consola.imprimirLinea(nombre);
                consola.imprimirLinea(edad);
            }
        }
    }
}
```

El ejemplo es ejecutable con `CompilarIr --ejecutar` en H2. No necesita Java Scanner,
System.out, una clase escrita por el usuario ni un archivo `Consola` local.

| Método de instancia público | Retorno | Regla |
|---|---|---|
| `consola.imprimir(valor)` | void | Escribe la representación textual del valor, sin salto de línea. |
| `consola.imprimirLinea(valor)` | void | Escribe el valor y después un LF. |
| `consola.imprimirLinea()` | void | Escribe únicamente un LF. |
| `consola.leerInt()` | int | Consume una línea y convierte todo su contenido a int64. |
| `consola.leerFloat()` | float | Consume una línea y convierte todo su contenido a binary64 finito. |
| `consola.leerBool()` | bool | Consume una línea con `true` o `false`. |
| `consola.leerLinea()` | string, que puede ser null | Devuelve la línea sin terminador; null indica EOF sin datos. |

Las lecturas tienen cero argumentos. Para mostrar un mensaje se usa primero
`imprimir`; no hay sobrecargas con prompt. `imprimir()` sin argumento es error
semántico. Solo `imprimirLinea` admite cero o un argumento. No se añaden lectura
de listas/objetos, formatos `%d`, archivos, terminal en modo crudo, colores ni
lectura de teclas en RC-1.

`valor` en la tabla es una regla intrínseca, **no un nuevo tipo del lenguaje**:
admite int, float, bool, string, null, listas con tipo de elemento completo y
objetos. Rechaza void y tipos incompletos/error. Por ejemplo, se imprime una
lista vacía a través de `list<int> xs = []; consola.imprimirLinea(xs);`.
`consola.imprimir([])` o `consola.imprimir([null])` sin un tipo deducible se
rechazan; estas llamadas no introducen un contexto de tipo de elemento.

El resultado void no puede asignarse ni utilizarse como operando. Los resultados
de lectura pueden asignarse, retornarse o usarse en expresiones bajo las reglas
de tipos vigentes; `var n = consola.leerInt();` infiere int. El lenguaje sigue
sin tener un tipo nullable separado: `leerLinea` usa las reglas actuales de
referencias string y null.

## 2. Clase integrada y resolución

`Consola` es una clase pública integrada, sin campos del usuario, con constructor
implícito de cero argumentos. Es cerrada a herencia: `class X extends Consola`
se diagnosticará cuando `Consola` designe la clase integrada. No se añade la
palabra clave `final` ni métodos estáticos al lenguaje.

Cada `new Consola()` crea una referencia nueva y no nula. Dos instancias son
distintas por identidad; todas operan sobre los mismos stdin/stdout del proceso.
Crear el objeto no lee, imprime, abre ni cierra esos canales. Reasignar una local
Consola a null está permitido; llamar mediante ella produce `REFERENCIA_NULA`.
Un alias conserva la instancia y se puede pasar como argumento ordinario.
Imprimir la propia instancia produce `<Consola>` conforme a H0.

El coordinador registrará la biblioteca si alguna unidad alcanzable contiene
`import runtime.Consola;`, tanto con validación de imports locales activa como
desactivada. La clase será visible en el registro global compartido del proyecto,
igual que las clases de las unidades ya cargadas. Imports repetidos de esa ruta
son idempotentes. El import integrado no cuenta como dependencia de archivo ni
se analiza como otra unidad a la que exigir un main.

La ruta exacta queda reservada a esta biblioteca: se resuelve como biblioteca
integrada antes de buscar archivos locales. Otras rutas siguen el comportamiento
de imports vigente. No se cargan bibliotecas Java externas ni se presupone que
`import otra.Consola;` sea esta biblioteca.

Sin el import integrado, una clase del usuario llamada `Consola` mantiene su
comportamiento ordinario. Si el import integrado se combina con una declaración
de usuario llamada `Consola` en cualquier unidad del proyecto, se diagnostica
conflicto de tipo: no se sustituye silenciosamente una clase por la otra.
Métodos llamados `imprimir` o `leerInt` en otras clases continúan siendo métodos
del usuario. **La identidad resuelta del símbolo**, y no su nombre textual,
determinará que una llamada pertenece al runtime.

Los métodos integrados se registrarán con descriptores de aridad, tipos y efecto.
Para imprimir, el descriptor aplica la regla de valor imprimible, sin crear un
tipo ficticio ni usar ERROR como comodín. El AST conservará tipo de retorno,
receptor, símbolo y un identificador intrínseco inequívoco. Habrá un despacho
RUNTIME separado del DIRECTO/VIRTUAL de métodos del usuario. Como Consola no
puede derivarse, no hay selección virtual ni sobrescritura de sus intrínsecos.
Esta es una extensión explícita al comportamiento de bibliotecas en H0.

## 3. Salida y orden de efectos

La salida normal va a stdout, los diagnósticos a stderr. RC-1 usa UTF-8 y LF,
independientemente de locale. No agrega espacios, prefijos ni separadores entre
llamadas. Un valor se evalúa **una sola vez** y se convierte con las reglas
`A_STRING` de H0: decimal int64, formato float acordado, booleanos en minúsculas,
null como `null`, listas con corchetes y objetos con su clase dinámica.

Las cadenas se escriben por longitud en bytes: se conservan UTF-8 y NUL internos,
sin truncamiento en `\u0000`. Una cadena con `%d` imprime esos dos caracteres;
el contenido del usuario no es una cadena de formato de libc. Una barra inversa
seguida de n en un literal imprime esos caracteres, dado que el lenguaje no
interpreta escapes. `imprimirLinea` añade el byte 0A, también para texto vacío.

Después de cada llamada de salida se vacía el búfer de stdout y se comprueba
el resultado. Así, `consola.imprimir("Edad: "); consola.leerInt();` muestra el
prompt incluso con stdout redirigido. No se promete persistencia en disco ni
atomicidad frente a otros procesos. Un fallo puede ocurrir tras escribir un
prefijo; no se repite la operación completa y no se ejecuta la sentencia siguiente.

Se conserva el orden H0: receptor, argumentos de izquierda a derecha,
comprobación del receptor y ejecución del intrínseco. Por tanto, si una llamada
de imprimir tiene receptor nulo y su argumento llama a leer, esa lectura sucede
antes del error del receptor exterior. `consola.imprimir(consola.leerInt())`
consume una línea y después imprime el valor; no la consume dos veces.
Los intrínsecos se ejecutan también en inicializadores cuando corresponda.

## 4. Lectura de líneas

Cada lectura consume una línea completa de stdin. El terminador es LF o CRLF;
ambos se quitan. Un CR que no precede a LF es contenido, no otro terminador.
Una línea vacía produce la cadena vacía, no null. Si EOF llega después de bytes
sin LF, se devuelve esa última línea y la siguiente lectura devuelve null.
Un CR final sin LF se conserva. EOF queda persistente en ese canal.

El contenido debe ser UTF-8 válido. Se rechazan secuencias truncadas, bytes de
continuación aislados, codificaciones demasiado largas, sustitutos y puntos
mayores que U+10FFFF; no se reemplazan con caracteres de sustitución. NUL es
contenido válido para `leerLinea`. No se elimina BOM ni se normaliza Unicode.

Límite de contenido normalizado por línea: **1 048 576 bytes** (1 MiB), excluidos
los bytes del terminador. El lector debe limitar la reserva mientras consume
la entrada; no leer una línea de tamaño ilimitado y comprobar después. Puede
necesitar hasta dos bytes extra para reconocer CRLF en el límite. El primer
byte de contenido que exceda el límite produce `LINEA_DEMASIADO_LARGA` y finaliza
el programa; no hace falta drenar ni reintentar la línea tras ese fallo.

Las lecturas esperan datos si stdin permanece abierto sin una línea completa
ni EOF. Esto es espera normal por entrada, no un bloqueo del compilador. Con
redirecciones y pipes se mantienen las mismas reglas. No hay timeout del
lenguaje ni sustitución automática de un dato ausente por cero o cadena vacía.

## 5. Conversión de una línea a un escalar

`leerInt`, `leerFloat` y `leerBool` usan el mismo lector anterior. Quitan solo
espacios ASCII de los extremos: espacio, tabulador, CR, tabulador vertical y
salto de página (20, 09, 0D, 0B, 0C hex). No permiten espacios internos, NUL,
separadores de miles ni espacios Unicode. Después comprueban la cadena entera,
no un prefijo. Todas consumen su línea; no dejan un salto pendiente para
`leerLinea`, ni dividen `12 34` en dos lecturas de enteros.

| Lectura | Forma admitida después del recorte | Conversión |
|---|---|---|
| int | `[+-]?[0-9]+` | Decimal int64; ceros iniciales permitidos. |
| float | `[+-]?[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?` | binary64, redondeo al más próximo con empate al par; debe ser finito. |
| bool | exactamente `true` o `false` | bool. No acepta 1/0, sí/no ni cambio de mayúsculas. |

El formato de datos de consola es independiente de la gramática de literales
fuente: leer `1e3` está permitido, aunque escribir `float x = 1e3;` en un programa
continúa siendo inválido. Float acepta `12` como 12.0; rechaza `.5`, `1.`, sufijos,
hexadecimales, coma decimal, NaN e Infinity. El underflow puede redondear a cero
y conserva su signo; el overflow a infinito produce `ENTRADA_FUERA_DE_RANGO`.
La longitud del exponente no introduce por sí sola otro límite de rango.

Una línea vacía o formada solo por espacios es `ENTRADA_INVALIDA` para escalares.
`12abc`, `12 34` y `1.2.3` se rechazan por formato, sin aceptar el prefijo.
Un formato válido fuera del rango int64/float64 produce
`ENTRADA_FUERA_DE_RANGO`. Se comprueba todo el formato antes del rango.

Ante EOF sin datos, `leerLinea` devuelve null. Una lectura escalar produce
`FIN_DE_ENTRADA`; no hay un valor de éxito para asignar al destino. En RC-1 los
errores de formato/rango son fatales: no hay reintento automático, diálogo ni
excepciones capturables añadidas al lenguaje. Esto queda visible al usuario,
en lugar de parecer una validación interactiva ya implementada.

Orden de diagnóstico en lecturas: límite mientras se consume, fallo de lectura
si aparece antes de completar la línea, validación UTF-8 de la línea completada,
formato del escalar y finalmente rango. En un fallo físico tras una línea parcial
no se devuelve esa línea como si hubiera llegado EOF.

## 6. Fallos y finalización

| Código observable | Causa |
|---|---|
| `FIN_DE_ENTRADA` | EOF sin datos en lectura escalar. |
| `ENTRADA_INVALIDA` | Línea completa que no satisface el formato del tipo. |
| `ENTRADA_FUERA_DE_RANGO` | Número con formato válido fuera del rango permitido. |
| `UTF8_INVALIDO` | Línea con codificación inválida. |
| `LINEA_DEMASIADO_LARGA` | Contenido superior a 1 MiB. |
| `ERROR_LECTURA_CONSOLA` | Fallo del canal de entrada. |
| `ERROR_ESCRITURA_CONSOLA` | Fallo de escritura o vaciado de stdout. |
| `MEMORIA_AGOTADA` | Fallo de reserva para lectura, conversión o formato. |
| `REFERENCIA_NULA` | Receptor Consola nulo, conforme a H0. |

El adaptador del intrínseco adjunta archivo/línea/columna de la llamada original.
El formato del diagnóstico termina en LF:

```text
Error de ejecución [CODIGO] "archivo":linea:columna: explicación
```

Los nombres de archivo usan los escapes del impresor IR para que un salto en
la ruta no cree varias líneas de diagnóstico. El código, origen y estado de
salida son estables; la explicación del sistema puede variar. No se imprime el
diagnóstico en stdout ni se afirma que una salida parcial haya sido revertida.
Un fallo en init/shutdown, sin llamada fuente correspondiente, usa el origen
explícito `"<generado>":0:0`; no se inventa una posición en el programa del usuario.

Se finaliza con estado 1, se libera la memoria registrada conforme a H0 y no
se continúa el programa. El diagnóstico evita depender de nuevas reservas para
reportar memoria agotada. Si stderr también falla, se mantiene estado 1 aunque
no sea posible presentar el mensaje. Las interrupciones recuperables de una
operación del sistema se reintentan donde no dupliquen bytes ya escritos.
El runtime Linux controla SIGPIPE para convertir un pipe roto en error de
escritura con estado 1. En Windows, el error de escritura del pipe se traduce
al mismo código y estado conforme al contrato multiplataforma, sin usar SIGPIPE.

## 7. IR y ejecución de referencia previstas

Se añadirá `LLAMAR_RUNTIME` con ID de un catálogo cerrado. Sus operandos son
receptor Consola seguido de argumentos, con origen y resultado tipado; no es
un método del usuario sin cuerpo ni una llamada C elegida por texto libre.

| ID interno RC-1 | Operandos IR | Resultado | Efecto |
|---|---|---|---|
| `CONSOLA_IMPRIMIR` | Consola, string | ninguno | escribe y vacía stdout |
| `CONSOLA_IMPRIMIR_LINEA` | Consola, string | ninguno | escribe texto/LF y vacía stdout |
| `CONSOLA_LEER_INT` | Consola | int | lee stdin, puede fallar |
| `CONSOLA_LEER_FLOAT` | Consola | float | lee stdin, puede fallar |
| `CONSOLA_LEER_BOOL` | Consola | bool | lee stdin, puede fallar |
| `CONSOLA_LEER_LINEA` | Consola | string | lee stdin; null si EOF, puede fallar |

Para imprimir se evalúa el argumento, se comprueba el receptor y se usa
`CONVERTIR A_STRING` antes de `LLAMAR_RUNTIME`. El receptor se comprueba antes
de esa conversión adicional para que un fallo de formato/reserva no desplace
el fallo de receptor nulo. La comprobación de receptor sigue siendo obligatoria
dentro de LLAMAR_RUNTIME aunque no se emita separadamente.
La sobrecarga `imprimirLinea()` baja como texto vacío, con el mismo ID de salida.
`CONVERTIR A_STRING` usa los mismos formateadores que la concatenación; no hay
una segunda política de texto especial para consola.

El verificador validará el ID, aridad, tipos exactos, resultado, identidad de la
clase integrada y origen. Cada operación tiene efectos de I/O y puede fallar;
no se elimina ni se reordena como una expresión pura. El receptor se conserva
para verificar semántica aunque el helper nativo use los canales compartidos
sin recibir el objeto.

El intérprete usará un contexto por ejecución con canales inyectables de bytes,
UTF-8 estricto y resultados/errores observables. No usará un Scanner o lector
global persistente ni cerrará stdin/stdout del compilador. Todas las Consola
de esa ejecución comparten su contexto. Se compararán bytes, estado de salida
y errores con el ejecutable nativo usando las mismas entradas suministradas.

Estas instrucciones y el catálogo se incorporaron al modelo IR en H2. La fase
de implementación deberá añadirlos antes de emitir llamadas de consola.

## 8. Frontera NASM ↔ runtime C

Las declaraciones están en `runtime/nativo/include/rt_console.h`. La ABI de
consola es versión 1, independiente del número de contrato H0. El prefijo
`rt_console_v1_` identifica símbolos de biblioteca, no nombres del lenguaje.
Se usarán funciones C no variádicas y tipos de tamaño fijo. `RtString` es un
handle opaco; H4 fija su layout compartido con conversiones y memoria en
[RUNTIME_NATIVO_H4.md](RUNTIME_NATIVO_H4.md).

| Helper | Resultado lógico |
|---|---|
| `rt_console_v1_init(error)` | Inicializa el servicio y manejo de errores antes de inicializar objetos del programa. |
| `rt_console_v1_write(texto, agregar_lf, error)` | Escribe bytes y vacía stdout; texto ya convertido no nulo. |
| `rt_console_v1_read_line(out, error)` | OK con string; EOF con out=null; resto es fallo. |
| `rt_console_v1_read_int/float/bool(out, error)` | OK con valor; EOF y demás estados son fallos para el lenguaje. |
| `rt_console_v1_shutdown(error)` | Vacía salida al finalizar; no cierra los descriptores estándar. |

El estado retorna en EAX; valores/punteros se entregan por parámetros de salida
y solo son válidos en éxito, con la excepción explícita del null de EOF en
read_line. Esto evita confundir int64 −1, float NaN o false con códigos de error.
El descriptor de error contiene código, errno capturado cuando corresponda y
posición de byte opcional; el origen del programa lo aporta el adaptador NASM.
ABI_VERSION devuelve 1 sin efectos. Init prepara entrada/salida y notifica
READ_ERROR o WRITE_ERROR si falla su preparación (incluido el manejo de SIGPIPE),
o OUT_OF_MEMORY si necesita una reserva que falla. Shutdown solo devuelve OK
o WRITE_ERROR. Write no devuelve EOF ni estados de conversión de entrada.

Las funciones reciben punteros en RDI/RSI/RDX… conforme a System V AMD64; el
caller mantiene RSP alineado a 16 antes de call y conserva sus valores vivos
frente a registros que una llamada puede destruir. Al devolver un float por
puntero no hay que codificar error y valor simultáneamente en XMM0. Esta frontera
se apoya en la [documentación oficial de NASM para C de 64 bits](https://www.nasm.us/doc/nasm13.html#section-13.3);
H4 define los puentes y llamadas de lenguaje en `RUNTIME_NATIVO_H4.md`.

Los handles devueltos por read_line pertenecen al gestor del runtime y viven
hasta el final del proceso; el caller no usa free sobre ellos. Los buffers
temporales internos de lecturas escalares pueden reciclarse o liberarse al acabar
la conversión. Write toma prestada la cadena y no la modifica ni la libera.
El servicio es de un solo hilo, con una inicialización por ejecución y una
finalización normal. La implementación podrá usar libc con lectura acotada y
escritura por longitud, control de errores y locale decimal independiente.

La conversión float a texto debe cumplir el formato acordado en H0, cuya
referencia es [Double.toString de Java 11](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/Double.html#toString(double)).
La implementación nativa y los oráculos deben fijar esa referencia: no tomar
el formateo de otra versión de Java o el `%g` predeterminado como equivalentes
sin comprobarlos. LeerFloat tampoco delegará ciegamente en un parser que
permita prefijos, NUL, hexadecimales o locale con coma.

## 9. Oráculos y criterio de aceptación

`runtime/especificacion/consola_v1.json` contiene casos de comportamiento
independientes de una implementación. El runner H2/H3 `tools/probar_consola.py`
ejecuta 89/90 contra el adaptador Java; `tools/probar_runtime_nativo.py` ejecuta
90/90 contra C, incluido pipe roto real. La reserva fallida se inyecta de forma determinista. Distingue retorno, EOF y fallo e incluye
datos binarios mediante hex. Números se representan como texto en el fixture
para no perder los límites int64 ni el signo de cero en consumidores JSON.

La implementación se aceptará cuando:

1. El frontend habilite solo la biblioteca explícita, resuelva su identidad y
   rechace conflictos de tipo, herencia, aridades, void y receptores incompatibles.
2. La IR verificada exprese estas operaciones con origen, efectos y resultado
   correctos; no fabrique cuerpos vacíos ni despachos de usuario para los helpers.
3. Intérprete y runtime nativo cumplan los oráculos de bytes, rangos, EOF,
   Unicode y formato, con expectativas independientes y plazos en el harness.
4. Se comprueben evaluación única, aliases/instancias, cortocircuito, receptores
   nulos y llamadas anidadas mediante contadores/entradas observables.
5. Se simulen fallos de lectura, escritura, flush, memoria y stderr; pipe roto
   termine con estado 1. Escrituras parciales nunca se dupliquen al reintentar.
6. Se verifiquen líneas de exactamente 1 MiB y mayores, UTF-8 partido entre
   lecturas, CRLF en el límite, lectura sucesiva tras escalar y última línea sin LF.
7. Los ejecutables ensamblados con NASM funcionen con terminal, stdin redirigido
   y stdout capturado, sin JVM, con la regresión previa del frontend intacta.

Secuencia de implementación: primero biblioteca integrada/descriptores/AST y
catálogo IR; después intérprete y oráculos. H2 cerró escalares y cadenas básicas;
H3 añadió impresión de listas/objetos y fallos de memoria simulados. Un generador
que no soporte un caso válido debe diagnosticarlo explícitamente. ABI/layout y
helpers C se implementan en H4, emisión NASM/enlace del programa completo en H5
y acciones de UI en H6. **La API es ejecutable en referencia y biblioteca C;
la aceptación del compilador nativo completo requiere los criterios de H5.**

## 10. Requisitos críticos de implementación

Estos puntos se incorporan como requisitos de aceptación de RC-1, no como
cambios de su API ni autorización para presentar un prototipo como conforme.

### Float: representación y conversión estrictas

La política de salida exige tanto el formato como los dígitos de Java 11:
`.0` cuando corresponda, ceros con signo, exponentes con E y cambio de notación
en 10⁻³ y 10⁷. No basta ajustar `%g`/`%f` ni garantizar round-trip. El propio
[proyecto Ryu](https://github.com/ulfjack/ryu#ryu) documenta diferencias con
Double.toString en ciertos valores; una representación más corta puede
reconstruir el mismo double y aun así no cumplir el texto acordado.

Ryu en C es un candidato a evaluar con una capa de compatibilidad. Dragonbox
también puede evaluarse, teniendo en cuenta que su
[implementación de referencia es C++](https://github.com/jk-jeon/dragonbox#language-standard)
y requeriría un wrapper con ABI C y ajustar el enlace. No se incorpora una
dependencia ni se declara equivalente una implementación por su nombre.
La selección debe superar un corpus de Java11 fijado, registrando versión de
fuente o proveedor/build del JDK11, bits binary64 originales y texto esperado.
H4 porta el FloatingDecimal original de jdk-11+28; usa esa fuente fijada como
oráculo y registra por separado el JDK anfitrión que ejecuta la copia. Incluir
valores adyacentes a los umbrales, subnormales, extremos, empates y valores con
diferencias conocidas. El JDK 25 instalado no sustituye ese oráculo versionado.

Un formateador temporal con snprintf podrá usarse en un prototipo identificado
como experimental, con diferencias visibles y casos que fallen conservados.
No habilita cerrar la compatibilidad de RC-1 ni cambiar las expectativas para
hacerlo pasar. El intérprete y el backend no serán sus propios únicos oráculos.

Para entrada se requieren **ambas** comprobaciones: autómata/gramática decimal
ASCII sobre todos los bytes tras el recorte, y consumo completo por el conversor.
`endptr` al final por sí solo no excluye hexadecimales o NaN/Inf, que
[strtod acepta](https://man7.org/linux/man-pages/man3/strtod.3.html).
Después de validar la gramática, si se usa strtod/strtod_l:

1. Rechazar NUL interno y reservar una copia terminada en NUL con longitud conocida.
2. Usar locale numérico C explícito, sin depender de LC_NUMERIC del entorno.
3. Limpiar errno antes de convertir y verificar que hubo conversión y que endptr
   coincide con el final por longitud, no solo que apunta a un byte cero.
4. Rechazar resultados no finitos de una entrada decimal válida como fuera de
   rango. Un ERANGE de underflow no es rechazo automático: RC-1 permite cero
   y subnormales, con signo y redondeo correctos.
5. Publicar el resultado solo al completar todas las comprobaciones; no asignar
   al destino del lenguaje un prefijo convertido o un valor de fallo.

Los casos adicionales cubren hexadecimal con exponente, Inf, NaN con payload,
prefijos válidos con basura y NUL. La aceptación bajo entornos con coma decimal
y el corpus de redondeo de entrada se verificarán en las pruebas de implementación.

### UTF-8: DFA incremental por bytes

Se usará un DFA o máquina de estados equivalente. Sus restricciones mínimas:
ASCII 00–7F; inicios C2–DF, E0–EF y F0–F4; continuaciones 80–BF. E0 exige
segunda parte A0–BF; ED limita a 80–9F; F0 exige 90–BF; F4 limita a 80–8F.
Estas fronteras excluyen overlong, sustitutos y valores mayores que U+10FFFF.
C0/C1, F5–FF y continuaciones sin inicio se rechazan.
Estas fronteras corresponden a la sintaxis de bytes de
[RFC 3629, §4](https://www.rfc-editor.org/info/rfc3629/).

El estado se conserva entre bloques de lectura; no se reinicia al llenar el
buffer ni se valida cada bloque como si fuese una cadena completa. Una secuencia
pendiente al terminar la línea es inválida. NUL ASCII sigue siendo válido para
leerLinea. Se conservará el primer error/posición, sin volver a aceptar la línea
por un reinicio accidental del estado.

Si la validación es incremental, se registra el fallo y se mantiene el orden
de diagnóstico de §5: límite/fallo de canal antes de reportar UTF-8 de una línea
completada. No se impone un número de líneas de C ni una tabla de terceros:
se exige corrección en fronteras y con secuencias divididas en varios bloques.

### SIGPIPE: preparación y error de canal

Init establecerá SIGPIPE a SIG_IGN antes de cualquier salida del programa,
incluidos inicializadores. En Linux se usará preferentemente sigaction con
máscara inicializada y retorno comprobado. El
[manual de signal](https://man7.org/linux/man-pages/man2/signal.2.html)
recomienda sigaction para instalar la disposición. Un fallo de preparación
se devuelve como WRITE_ERROR con errno capturado, conforme a §8.

Con SIGPIPE ignorada, una escritura a un pipe sin lectores puede devolver
EPIPE en lugar de terminar por la señal; el
[contrato de write](https://man7.org/linux/man-pages/man2/write.2.html)
describe ambos comportamientos. EPIPE se mapea a ERROR_ESCRITURA_CONSOLA,
sin reintentar bytes, y el adaptador finaliza con estado 1. La disposición se
mantiene durante flush y el reporte a stderr; no se restaura antes de un
diagnóstico que también pudiera ir a un pipe roto. Si stderr falla, prevalece
el estado 1 sin intentar imprimir errores recursivamente.

Se probará con un pipe real cuyos extremos lectores se hayan cerrado antes
de la escritura, además del canal de fallo inyectado. El harness comprobará
estado 1 y ausencia de terminación por SIGPIPE; no dependerá de la carrera
temporal de `programa | head`. La prueba de escritura parcial seguirá exigiendo
que el prefijo se escriba una vez y que no continúe la secuencia.
