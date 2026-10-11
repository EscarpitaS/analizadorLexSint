# Runtime nativo y layout: H4

H4 documenta el diseño original Linux. H5 amplía los perfiles y puentes a
Windows/UCRT, separando I/O y locale en rt_platform.h/.c. Los detalles System V
siguientes se conservan para Linux; la implementación Windows y su estado de
validación están en [GENERACION_NATIVA_H5.md](GENERACION_NATIVA_H5.md).

Fecha: 10 de octubre de 2026. Destino NASM/Linux x86-64/ELF64, System V AMD64.
H4 incorpora biblioteca C11, representación de datos y puentes ABI del runtime.
H5 ya emite los cuerpos y la entrada CRT desde la IR atómica.

## Entregables

- `runtime/nativo/include/rt_runtime.h`: ABI común de memoria, datos, errores y
  adaptadores; conserva RC-1 en `rt_console.h`.
- `runtime/nativo/src/`: implementaciones de consola, memoria, formato y helpers.
- `DatosNativosIr`: pool UTF-8, descriptores de tipos/clases, vtables y orígenes.
- `SoporteNativoIr`: datos estáticos y puentes NASM entre LLAMAR_RUNTIME y C.
- `CompilarIr --soporte-nativo salida.asm`: exportación verificable del soporte.
- `tools/compilar_runtime.py`: biblioteca `.a` y `.so`, sin JVM.
- `tools/probar_runtime_nativo.py`: oráculos, comprobaciones C y pruebas de enlace.

El soporte `.asm` contiene datos y adaptadores, sin cuerpos de los métodos fuente
ni main del sistema. Sus referencias a `fn_*` se resuelven con el backend H5.
Las pruebas H4 enlazan esas referencias con implementaciones de prueba explícitas;
no presentan ese harness como compilación completa de un programa del lenguaje.

## Layout compartido

Todos los valores, direcciones y referencias usan ranuras de 8 bytes. int es
int64; float es IEEE binary64; bool se normaliza a 0/1; null es puntero cero.
Los bits de un double se trasladan en C mediante memcpy, evitando aliasing ilegal.
Todos los handles y descriptores se alinean al menos a 8 bytes.

| Estructura | Offset y contenido |
|---|---|
| RtString | +0 longitud uint64 de bytes; +8 reservado cero; +16 bytes UTF-8. |
| RtList | +0 longitud uint64; +8 puntero RtType del elemento; +16 ranuras de 8 bytes. |
| RtObject | +0 puntero vtable; +8 puntero RtClass de clase dinámica; +16 campos. |
| RtType, 24 bytes | +0 categoría; +8 descriptor del elemento o cero; +16 descriptor de clase o cero. |
| RtClass, 48 bytes | +0 handle del nombre; +8 tamaño total; +16 vtable; +24 base; +32 número de campos; +40 tabla de sus tipos. |
| RtOrigin, 24 bytes | +0 nombre de archivo UTF-8 terminado en NUL; +8 línea int64; +16 columna int64. |
| RtConsoleError, 16 bytes | +0 estado uint32; +4 errno int32; +8 posición uint64 del byte. |

Las categorías RtType son int=1, float=2, bool=3, string=4, null=5, objeto=6,
lista=7. Los descriptores son inmutables, incluidos los elementos anidados.
Los campos base conservan su prefijo; dos campos homónimos tienen ranuras distintas.
Las vtables mantienen slots heredados y sustituyen públicos sobrescritos;
los privados y `init_IR` no participan en ellas.

RtString añade un NUL de conveniencia después de sus bytes, fuera de la longitud.
Se imprime, concatena y compara por longitud: los NUL internos se conservan.
Las constantes incluyen esa misma cabecera y pueden residir en `.rodata`.
Los datos con punteros usan `.data.rel.ro`. El pool evita duplicar una constante
idéntica y exporta los bytes numéricamente, sin interpretar escapes del ensamblador.
Los float constantes continúan como hojas por bits en la IR, sin convertir a decimal.

`rt_entry_class`, `rt_entry_init` y `rt_entry_main` identifican el plan de entrada.
El envoltorio C `rt_run_program` llama a `rt_program_init`, reserva el receptor,
llama a su inicializador y main, y finalmente a `rt_program_shutdown`, devolviendo
0 si termina normalmente. H5 emite main del sistema para invocarlo con esos
descriptores y punteros de métodos. El inicializador de
cada new sigue siendo un CALL explícito después de reservar todos los campos.
El helper de reserva no interpreta AST ni ejecuta inicializadores ocultos.

## Memoria y helpers

El gestor registra reservas y libera todas al terminar. No recoge objetos durante
la ejecución. Los descriptores y constantes estáticos no se registran ni liberan.
Los handles gestionados no se liberan desde código generado. Las operaciones
comprueban overflow de tamaños antes de reservar. Un objeto asigna cero a todos
los campos antes de inicializadores; esos bits representan los valores H0.
Las listas mantienen longitud fija y referencias compartidas para aliasing.

Los helpers implementan null, índice, reserva de objetos/listas, conversión a
texto, concatenación, igualdad de strings y división. La comprobación de índice
rechaza null antes del rango. La división entera comprueba cero y MIN_INT/-1
antes de dividir; ese caso retorna MIN_INT. La división float rechaza ambos
ceros con signo. El formateador de listas usa una pila explícita; objetos se
formatean con la clase dinámica sin recorrer campos ni llamar métodos de usuario.

Un fallo escribe el diagnóstico RC-1 con el origen capturado y termina con 1.
El diagnóstico no reserva memoria y conserva el mismo escape de archivo que el
impresor Java, incluidos controles y pares surrogate para caracteres no BMP.
Un stderr roto no provoca reintentos recursivos ni cambia el estado de salida.
El agotamiento de pila nativa conserva el límite del sistema acordado en H0.

## ABI de llamadas

Se conserva el layout atómico existente: ranuras respecto a RBP, marco múltiplo
de 16, área saliente fija, seis GPR y ocho SSE contados independientemente.
El receptor cuenta como argumento entero. Excedentes usan `[rsp+0]`, `[rsp+8]`…
y entran por `[rbp+16]`, `[rbp+24]`…; retornos en RAX o XMM0.
CALL_INDIRECT conserva R11 como registro de destino. No hay llamadas variádicas.

Cada símbolo lógico `rt_lang_*` usado en el módulo tiene un puente exportado.
El caller prepara los argumentos y carga en R10 un puntero al RtOrigin de la
instrucción justo antes de CALL. El puente captura R10 en el parámetro C de origen,
añade los descriptores estáticos necesarios y llama a la función real del runtime.
A_STRING de float mueve sus bits desde XMM0 a RDI; el descriptor identifica float.
Los puentes guardan RBP y mantienen RSP alineado; no conservan valores en R10/R11
entre llamadas. GCC conserva sus registros no volátiles conforme a System V.

La API C RC-1 retorna estado en EAX y datos por puntero out. Los adaptadores
`rt_lang_read_*` validan ese estado y entregan directamente el valor del lenguaje
en RAX/XMM0, o terminan con error. EOF de leerLinea se devuelve como null;
EOF escalar es FIN_DE_ENTRADA. Los out permanecen intactos en fallos físicos,
de formato/rango, UTF-8 y memoria, salvo el null explícito de EOF en read_line.

## Consola y float

Init instala SIG_IGN para SIGPIPE mediante sigaction comprobada y prepara locale
numérico C y redondeo al más próximo. La disposición se conserva hasta terminar,
incluidos shutdown y diagnósticos. Write usa longitud y reintenta EINTR/escrituras
parciales sin repetir prefijos. No mantiene un buffer de salida: todos los bytes
se transfieren antes de retornar. El hook de flush del harness simula un fallo
posterior al envío sin inventar buffers en producción. No se cierran stdin/stdout.

La lectura se realiza por bytes con buffer que crece hasta 1 MiB + dos bytes;
CRLF se normaliza incluso en el límite. Se valida UTF-8 mediante una máquina de
estados con fronteras E0/ED/F0/F4, después de completar la línea. Esto conserva
la precedencia de límite/fallo físico sobre UTF-8. El EOF permanece en el canal.
Leer escalares valida toda la gramática ASCII, recorta solo los espacios RC-1,
y después convierte. Float usa strtod_l con locale C y endptr exacto; acepta
underflow con signo y rechaza overflow no finito. NUL/hexadecimal/NaN/Inf y
prefijos con basura se rechazan antes de convertir.

El formato binary64 es un **puerto C del FloatingDecimal de OpenJDK jdk-11+28**.
Conserva las ramas int32/int64/bigint y sus pruebas de parada/redondeo: no reemplaza
el formato con printf, Ryu ni Double.toString del JDK anfitrión. Los big integers
son de 2048 bits, suficientes para los límites binary64, sin reserva dinámica.
Se desactiva la contracción de operaciones float y fast-math al compilar.
El archivo conserva GPL2 + excepción Classpath, licencia y procedencia en
`runtime/terceros/openjdk11/`. El resto del runtime utiliza operaciones C11/POSIX.

## Compilación y verificación

Desde la raíz del proyecto:

```sh
export JAVA_HOME=/home/sebastian/.jdks/ms-25.0.4.1
python3 tools/compilar.py
python3 tools/compilar_runtime.py
"$JAVA_HOME/bin/java" -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H3_objetos_listas.txt --soporte-nativo /tmp/soporte.asm
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
python3 tools/probar_runtime_nativo.py
```

`CC` selecciona GCC compatible y `--salida` permite otro directorio. La biblioteca
estática requiere enlazar libc y libm (`gcc ... libanalizador_runtime.a -lm`).
El runtime de producción excluye RT_TESTING. Los hooks de fallos/canales solo
aparecen en las bibliotecas temporales del harness.
Para incluir la verificación NASM, seleccionar `NASM` con la ruta al binario.
La proyección GNU as también ensambla y ejecuta los mismos puentes ABI. En esta
entrega se verificaron ambas, incluido NASM 3.02 compilado desde su fuente oficial.
El binario local está en `build/tools/nasm`, con licencia y procedencia; el runner
lo detecta cuando NASM no está en PATH. No se instalaron paquetes del sistema.

Resultados: **90/90 oráculos RC-1 nativos**, 13 comprobaciones ABI adicionales,
11 rutas de finalización, EINTR y escrituras parciales; 46 comprobaciones Java H4
integradas en regresión; pruebas C de layout, aliasing, NUL y listas a profundidad
4096. Los puentes enlazados verifican retornos, descriptores, vtables, origen R10
y conservación de RBX/R12–R15.

Float: **8809 casos versionados y 100 000 patrones diferenciales adicionales**.
El corpus versionado registra los bits originales, expectativas del código
OpenJDK11 fijado y hash de esa fuente; se ejecutó la copia con Microsoft OpenJDK
25.0.4.1, sin utilizar el formateador del JDK anfitrión. No se atribuye el corpus
a un binario JDK11 instalado. Incluye subnormales, exponentes, ceros, no finitos,
empates y vecinos de umbrales/potencias. La referencia Java y el puerto C se
comparan con ese corpus guardado y los oráculos JSON independientes.

`python3 tools/probar_runtime_nativo.py --sanitizers` habilita ASan/UBSan donde
estén instaladas sus bibliotecas. En este entorno no se pudieron enlazar porque
faltan libasan/libubsan; esa verificación no se declara aprobada.
H5 implementa ahora cuerpos, entrada y builder completo por destino. Linux
está verificado nativamente; Windows bajo Wine requiere aún aceptación Windows.
