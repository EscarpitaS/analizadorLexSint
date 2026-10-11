# H5: generación NASM compartida y adaptadores de plataforma

Fecha: 10 de octubre de 2026.

Implementados los cuerpos completos y la entrada CRT de Linux x64 y Windows x64.
La validación Linux ejecuta programas nativos sin JVM. Windows se ensambla como
COFF, se enlaza con UCRT y se comprueba bajo Wine; esto no reemplaza la aceptación
en Windows, especialmente consola interactiva Unicode y desenrollado real.
La integración de ASM/herramientas en Swing corresponde a H6.

## Organización didáctica

```text
Frontend → IR tipada portable
               ↓ perfil DestinoNativoIr
     LinealizadorIr → IR atómica y layout verificados
               ↓
 GeneradorEnsamblador: cuerpo x86-64 compartido
               + AdaptadorPlataformaNasm: ABI, prólogos, entrada y desenrollado
               + SoporteNativoIr: datos y puentes al runtime
               ↓
            NASM → objeto → enlace con runtime → ejecutable
```

No se duplican los algoritmos de expresiones/control/objetos. El backend no
consulta AST ni símbolos semánticos. Se mantienen las operaciones y operandos
hoja de CuadruploIr; LayoutProgramaIr registra el perfil que calcula sus offsets
ABI. Los constructores anteriores siguen seleccionando Linux por defecto.
El verificador comprueba argumentos y marcos según ese perfil y rechaza mezclas.

El ASM separa mediante comentarios el soporte, los adaptadores y las operaciones
del programa. Cada cuádruplo tiene un comentario `IR #N OPERACION @ :linea:columna`,
con índice cero por función, seguido por las instrucciones que lo implementan.
Una primitiva IR puede requerir varias instrucciones para cargar, operar y guardar.
Las vistas didácticas existentes se conservan y no se usan para compilar.

El núcleo comparte movimientos, memoria, enteros con wrap, SSE2 binary64,
conversiones, comparaciones y saltos. Las comparaciones float filtran el estado
unordered para respetar NaN y los ceros con signo. División se resuelve por los
helpers H4 que controlan cero y MIN_INT/-1. Los valores permanecen en sus ranuras
de pila entre operaciones. Solo se usan RAX/RDX y XMM4/XMM5 como scratch del
cuerpo; los parámetros se preparan desde memoria, sin pisar los ya preparados.
CALL_INDIRECT recarga R11 después de PARAM; R10 transporta el origen del runtime.

## Adaptadores

Linux conserva System V, ELF64, PLT, secciones H4 y enlace sin PIE.
Windows usa posiciones compartidas RCX/RDX/R8/R9 o XMM0…3, incluidos receptor y
argumentos float. Los excedentes usan [rsp+32] y [rbp+48]. Reserva conservadora
mínima de 32 bytes, también en hojas, y marcos múltiplos de 16. El cuerpo evita
registros no volátiles adicionales; RBP se guarda/restaura en cada función.

Cada marco Windows de 4096 bytes o más llama a `___chkstk_ms` de MinGW-w64 antes
de reservar. Se emiten .pdata/.xdata con UNWIND_INFO v1, PUSH_NONVOL RBP y la
reserva exacta SMALL/LARGE; RSP permanece fijo durante el cuerpo y se restaura
con ADD/POP/RET. No se declara frame register en UNWIND_INFO: RBP sirve para
acceso a ranuras, mientras el desenrollado usa el RSP fijo y la reserva registrada.
Los puentes y main también tienen sus registros de desenrollado.

Referencias de formato: [NASM win64](https://www.nasm.us/doc/nasm09.html),
[Microsoft unwind](https://learn.microsoft.com/en-us/cpp/build/exception-handling-x64),
[prólogos/sondeo](https://learn.microsoft.com/en-us/cpp/build/prolog-and-epilog).

El runtime conserva memoria, layout, parsing estricto y FloatingDecimal Java11.
`rt_platform.h/.c` encapsula SIGPIPE, I/O y locale POSIX o Win32. Windows usa
ReadFile/WriteFile para canales redirigidos, sin traducción CRT; en consola usa
ReadConsoleW/WriteConsoleW, conversión estricta UTF-16/UTF-8 y detección de
surrogates aislados. Pipes rotos se traducen a EPIPE y al error RC-1 existente.
Stderr también usa el adaptador. Referencias: [ReadConsole](https://learn.microsoft.com/en-us/windows/console/readconsole),
[WriteConsole](https://learn.microsoft.com/en-us/windows/console/writeconsole).

## Exportar y construir

Compilador: JDK 11+ y Python 3. Ensamblado: NASM. Enlace Linux: GCC/binutils;
enlace Windows: MinGW-w64 con UCRT (GCC o LLVM compatible). El builder comprueba
arquitectura/destino con -dumpmachine y _UCRT en los headers de Windows.
El ejecutable resultante no necesita JVM. El runtime del proyecto se enlaza
estáticamente; las dependencias de sistema/CRT corresponden al destino.

Desde la raíz, con JAVA_HOME apuntando a un JDK 11+:

```sh
python3 tools/compilar.py
"$JAVA_HOME/bin/java" -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H3_objetos_listas.txt --entrada Programa --target linux-x64 --asm /tmp/programa.asm
python3 tools/compilar_nativo.py untitled/ejemplos/ir/H3_objetos_listas.txt --entrada Programa --target linux-x64
./build/nativo/linux-x64/H3_objetos_listas/programa
```

Para Windows desde Linux, seleccionar el compilador cruzado UCRT:

```sh
python3 tools/compilar_nativo.py untitled/ejemplos/ir/H3_objetos_listas.txt --entrada Programa --target windows-x64 --cc /ruta/toolchain/bin/x86_64-w64-mingw32-gcc
```

En Windows, configurar JAVA_HOME, NASM y GCC UCRT en PATH, y usar:

```powershell
python tools/compilar.py
python tools/compilar_nativo.py untitled/ejemplos/ir/H3_objetos_listas.txt --entrada Programa --target windows-x64 --cc gcc
.\build\nativo\windows-x64\H3_objetos_listas\programa.exe
```

`--etapa asm|objeto|ejecutable` permite detenerse y estudiar cada etapa.
`--salida` elige una carpeta nueva, `--nasm`, `--cc`, `--ar` y `--jar` seleccionan
herramientas/paquete. Variables equivalentes: NASM, CC/AR y CC_WINDOWS/AR_WINDOWS.
La omisión de --target conserva linux-x64; no se deduce del sistema anfitrión.

La carpeta publicada contiene programa.asm, ambas IR, objeto y ejecutable según
etapa e identidad-programa.json. El runtime contiene identidad-runtime.json con
destino/ABI/CRT. Los errores identifican generación, ensamblado, runtime o enlace,
con salida de la herramienta y plazo; un fallo no publica la carpeta final.
El builder rechaza una carpeta existente: para repetir, elegir otra --salida.
La CLI Java rechaza exportaciones sobre cualquier fuente del proyecto o salidas
que apunten al mismo archivo. No ejecuta el binario al compilar.

## Verificación y límites de aceptación

```sh
python3 tools/probar_h5.py
python3 tools/probar_h5.py --target windows-x64 --cc /ruta/toolchain/bin/x86_64-w64-mingw32-gcc --wine
python3 tools/probar_runtime_nativo.py
```

En Windows, ejecutar también las suites nativas:

```powershell
python tools/probar_h5.py --target windows-x64 --cc gcc
python tools/probar_runtime_nativo.py --target windows-x64 --cc gcc
```

PruebasH5 integra oráculos independientes y ambos intérpretes; exporta el corpus
para ensamblar/enlazar/ejecutar. Cubre todas las operaciones, recursión, llamadas
mixtas con excedentes de ambos bancos Linux y posiciones Windows, imports,
marcos grandes, herencia, aliasing, snapshots, NUL/Unicode, floats especiales,
evaluación y errores con stdout/stderr/estado. También rechaza ABI mezclada y
colisiones con símbolos del soporte. El runner prueba protección de fuentes,
destinos desconocidos, fuentes inválidas y enlace fallido sin publicación.

La validación Windows adicional incluye layout/memoria C y pipes rotos de
stdout/stderr mediante handles Win32 reales bajo Wine. Los 90 oráculos RC-1
con hooks, los casos float y las rutas nativas quedan disponibles en el runner
Windows. Sigue pendiente ejecutarlos en Windows y verificar allí consola Unicode
interactiva, registros/desenrollado y sondeo real del sistema. Por ese motivo,
H5 está implementado pero su aceptación multiplataforma aún no está cerrada.

Resultados de esta entrega: regresión completa y **362 comprobaciones H5**,
26 programas completos aprobados en Linux y el mismo corpus por compilación
cruzada/Wine para Windows, más 11 rutas de finalización Windows y layout/memoria C. El runner guarda resultados JSON y bytes reales
de stdout/stderr por caso. Las comprobaciones bajo Wine se identifican como tales.
