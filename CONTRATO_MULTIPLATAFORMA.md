# Contrato de destinos nativos: Linux y Windows x64

Fecha: 10 de octubre de 2026. Perfiles de plataforma versión 1.
Extiende [CONTRATO_EJECUCION.md](CONTRATO_EJECUCION.md); las reglas del lenguaje
siguen en versión 1. No cambia los modelos de IR ni los oráculos de RC-1.

**Estado:** H5 implementa los dos perfiles físicos, emisión NASM y runtime
POSIX/Win32. Linux se verifica nativamente; Windows se ensambla/enlaza con UCRT
y se comprueba bajo Wine. La aceptación nativa Windows y consola interactiva
siguen pendientes. Detalle y comandos en [GENERACION_NATIVA_H5.md](GENERACION_NATIVA_H5.md).

## Selección y separación de representaciones

La CLI de compilación acepta `--target linux-x64` o
`--target windows-x64`; por compatibilidad, la omisión seleccionará `linux-x64`.
El destino identifica el sistema del ejecutable, independientemente del sistema
que aloja al compilador. Un destino desconocido o no implementado se rechaza
antes de exportar artefactos físicos; nunca se sustituye silenciosamente por otro.

Se elige **antes de linealizar la IR tipada**: el perfil determina los argumentos,
marcos, puentes, secciones NASM, objetos y enlace. Cambiarlo obliga a reconstruir
la proyección física y todos los artefactos dependientes. La GUI deberá indicar
el destino de cada artefacto y conservar separadas sus salidas.

```text
Proyecto validado → IR tipada portable
                         ├→ vistas didácticas: cuádruplos / triplos / tres direcciones
                         └→ perfil linux-x64 o windows-x64
                                      ↓
                           IR física → NASM → objeto → enlace → ejecutable
```

La IR física Linux existente permanece intacta. El
perfil Windows parametriza las mismas operaciones y operandos físicos;
no exige cambiar la estructura de CuadruploIr. Las vistas de enseñanza son
proyecciones de consulta y no se usan como entrada del backend.

## Artefactos y herramientas

| Propiedad | linux-x64 | windows-x64 |
|---|---|---|
| CPU y orden de bytes | x86-64, little endian | x86-64, little endian |
| Ensamblador | NASM | NASM |
| Formato de objeto | ELF64, `-f elf64`, `.o` | Microsoft AMD64 COFF, `-f win64`, `.obj` |
| Ejecutable | ELF64 de consola | PE32+ x64 de consola, `.exe` |
| ABI | System V AMD64 | Microsoft x64 |
| Toolchain de referencia | GCC/binutils Linux | GCC/binutils MinGW-w64 con UCRT |
| Runtime del proyecto | biblioteca estática para Linux | biblioteca estática recompilada para Windows |
| JVM al ejecutar | ninguna | ninguna |

Formatos: [NASM](https://www.nasm.us/doc/nasm09.html),
[PE/COFF](https://learn.microsoft.com/en-us/windows/win32/debug/pe-format).
MinGW-w64 ofrece toolchains nativos y cruzados; el perfil selecciona UCRT
explícitamente, no lo infiere del nombre `gcc`.
[Toolchains de MinGW-w64](https://www.mingw-w64.org/downloads/).

El compilador sigue usando Java 11+. Los ejecutables usan únicamente el runtime
nativo y bibliotecas del sistema correspondientes. Compilar para Windows desde
Linux requiere toolchain cruzado completo. Las pruebas de aceptación Windows
se ejecutan en Windows; un ensamblado o enlace cruzado exitoso no acredita ejecución.

Se prevé almacenar artefactos en `build/nativo/<target>/`. Los objetos y la
biblioteca deben registrar destino y ABI. Mezclar objetos o runtime de destinos
diferentes es un error de compilación. Las ubicaciones H4 actuales siguen disponibles para sus pruebas; el builder H5
publica carpetas separadas por destino con identidades explícitas.

## ABI y marcos

El receptor cuenta como argumento lógico 0. No se hacen llamadas variádicas;
strings, listas y objetos viajan como punteros. Retornos escalares enteros,
bool y referencias usan RAX; float usa XMM0. El puntero de CALL_INDIRECT usa
R11, recargado tras preparar argumentos; R10 transporta el origen del helper.

| Distribución | linux-x64 | windows-x64 |
|---|---|---|
| Enteros y punteros | RDI, RSI, RDX, RCX, R8, R9 | posiciones 0…3: RCX, RDX, R8, R9 |
| float | XMM0…XMM7, banco independiente | posiciones 0…3: XMM0…XMM3 |
| Primer excedente saliente | `[rsp+0]` | `[rsp+32]` |
| Primer excedente entrante, tras `push rbp; mov rbp,rsp` | `[rbp+16]` | `[rbp+48]` |
| Área reservada para los cuatro argumentos iniciales | no requerida | 32 bytes de shadow space en cada llamada |

Windows cuenta posiciones compartidas, incluido el receptor. En `(objeto,
float, int, float, int)` usa RCX, XMM1, R8, XMM3 y pila respectivamente.
Los argumentos quinto y siguientes usan ranuras consecutivas de 8 bytes.
El shadow space pertenece al llamado durante la llamada y no guarda temporales
vivos del llamador. Referencia: [ABI Microsoft x64](https://learn.microsoft.com/en-us/cpp/build/x64-calling-convention).

En ambos perfiles, CALL deja RSP desalineado en 8; `push rbp` lo alinea a 16.
La reserva total fija del prólogo es múltiplo de 16 e incluye locales,
temporales, registros preservados y el área saliente máxima, sin solapamiento.
Cada puente C también reserva su área de llamada: el prólogo Linux actual
no puede reutilizarse literalmente como puente Windows.

Para Windows, cada función que llama reserva como mínimo 32 bytes salientes,
y añade `8 * max(0, argumentos_logicos - 4)` según su llamada de mayor aridad.
Una función hoja puede omitir el área si no contiene llamadas. Los bytes de
registros guardados también entran en el cálculo de alineación.

Windows conserva RBX, RBP, RDI, RSI, R12…R15 y XMM6…XMM15. Linux mantiene su
propio conjunto. Ningún backend presupone que los registros SSE superiores
sean temporales volátiles en ambos perfiles. Las funciones Windows con marco
o llamadas incluyen información de desenrollado `.pdata/.xdata` coherente con
su prólogo. [Convenciones de registros y desenrollado](https://learn.microsoft.com/en-us/cpp/build/x64-calling-convention).

Marcos Windows que puedan cruzar una página deben sondear la pila antes de
reservarla; la implementación resuelve el helper de sondeo de su toolchain.
[Prólogos y sondeo de pila](https://learn.microsoft.com/en-us/cpp/build/prolog-and-epilog).
El agotamiento de pila conserva el límite del sistema definido por H0.

## Datos, enlaces e inicio

Se mantienen ranuras de 8 bytes y cabeceras de 16 para objetos, listas y strings;
también los descriptores y el layout de H4. En C se usan int64_t/uint64_t,
uint32_t y punteros, con aserciones de tamaño/offset por destino. `long` no
representa el int del lenguaje. La longitud de strings siempre cuenta bytes UTF-8.

El backend usa las secciones y relocaciones de cada formato. `.rodata`,
`.data.rel.ro`, PLT y `.note.GNU-stack` del soporte Linux no se copian sin
adaptación a COFF. Los nombres lógicos de métodos/runtime permanecen estables;
el perfil define su representación y referencias externas.

Linux mantiene el enlace inicial sin PIE. Windows genera una aplicación de
consola con el CRT MinGW-w64/UCRT: el punto de entrada del CRT prepara el proceso
y llama a main. No se usa el main del lenguaje como entrada PE directamente.
El envoltorio conserva el ciclo init → receptor por defecto → inicializador →
main del lenguaje → shutdown, mediante rt_run_program y sus puentes ABI.

El runtime del proyecto se enlaza estáticamente en ambos destinos; las
bibliotecas del sistema no se incluyen como código del proyecto. Todas las
funciones C y auxiliares de toolchain se enlazan con el mismo perfil y CRT.
Los errores de ensamblado y enlace se reportan por etapa, sin presentar un
artefacto incompleto como ejecutable válido.

## Runtime RC-1 por sistema

Se conservan formatos Java 11 de float, gramáticas estrictas de entrada,
UTF-8 válido, NUL internos, LF de salida, CRLF de entrada, límite de 1 MiB,
EOF y códigos/orígenes/estado de salida. El núcleo de memoria y formateo se
comparte; la capa de consola, locales numéricos y finalización se adapta.

Linux usa la implementación POSIX H4 y controla SIGPIPE. Windows detecta
si cada handle estándar es consola o está redirigido. Con consola, usa lectura
y escritura Unicode y convierte estrictamente UTF-16 ↔ UTF-8; con archivo o
pipe, transporta bytes UTF-8 sin traducción de texto del CRT. La conversión
rechaza surrogates aislados y mantiene el límite en bytes UTF-8 normalizados.
[Consola y redirección](https://learn.microsoft.com/en-us/windows/console/high-level-console-input-and-output-functions),
[WriteConsole](https://learn.microsoft.com/en-us/windows/console/writeconsole),
[ReadConsole](https://learn.microsoft.com/en-us/windows/console/readconsole).

Las salidas redirigidas deben conservar exactamente los bytes, incluidos NUL
y LF sin convertirlo en CRLF. En consola interactiva se exige el contenido
Unicode equivalente; la apariencia del terminal no es un oráculo de bytes.
Una entrada redirigida con 0x1A contiene un byte de datos, no un EOF del CRT.

Windows no implementa el requisito mediante SIGPIPE: un pipe roto en salida
se convierte en ERROR_ESCRITURA_CONSOLA, diagnóstico en stderr y estado 1.
Un cierre normal del pipe de entrada se trata como EOF tras consumir sus datos;
los demás fallos de lectura son ERROR_LECTURA_CONSOLA. Se conservan escrituras
parciales y reintentos que no repitan bytes. Stderr roto mantiene salida 1.

Los estados RC-1 y RtConsoleError conservan versión y layout. `system_errno`
contiene errno de la capa C o una traducción documentada del error Win32:
EPIPE para pipe roto de salida, EBADF para handle inválido, ENOMEM para reserva
y EIO para otros fallos físicos. No se inserta GetLastError crudo en ese campo.
Los detalles del sistema son auxiliares; el código observable sigue siendo RC-1.
La conversión decimal usa locale C privado por plataforma y validación completa;
no depende del locale global ni sustituye Double.toString por `%g`.

## Aceptación y trabajo pendiente

Antes de cerrar la aceptación nativa Windows se exige:

- Proyección ABI parametrizada y verificadores para ambos perfiles; Linux sin regresiones.
- Runtime Windows: 90 oráculos RC-1 equivalentes, Unicode, NUL y canales redirigidos;
  el oráculo Linux de SIGPIPE se sustituye por pipe cerrado real con el mismo error.
- Llamadas mixtas, receptor incluido, más de cuatro argumentos, shadow space,
  registros preservados, llamadas indirectas, marcos grandes y desenrollado.
- Los mismos resultados que ambos intérpretes para control, aliasing, herencia,
  inicialización, listas, división MIN/-1 y todos los errores de H0.
- Programas completos ensamblados con NASM, enlazados y ejecutados en Linux y Windows;
  cada ejecución guarda bytes stdout/stderr, estado de salida y destino.
- Límites de tiempo y presupuesto solo en el harness de pruebas; no cambian el lenguaje.

La implementación H5 se documenta por separado de las vistas didácticas. Su
validación cruzada no cierra los requisitos de aceptación nativa Windows.
