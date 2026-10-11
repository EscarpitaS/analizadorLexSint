# H6: de la instantánea del editor al ejecutable nativo

Implementado el 11 de octubre de 2026. H6 integra H5 en Swing sin cambiar la
estructura, las operaciones ni los offsets de las IR existentes.

## Uso en la interfaz

1. Abre o escribe un proyecto y pulsa **Analizar**. Se usa el contenido del
   editor, incluidos los cambios sin guardar, y las unidades importadas del
   proyecto. Un error en cualquiera de ellas impide generar artefactos.
2. Abre **Resultados → ASM y ejecutable**. Selecciona **Linux x64** o
   **Windows x64** y la clase con `public main()`. El selector muestra los
   métodos declarados candidatos; la entrada inicial es la de la vista IR.
   La generación ASM empieza en segundo plano y se repite al cambiar la selección.
3. Consulta **ASM completo** y **IR física del destino**. Son exactamente los
   textos utilizados en la construcción, con la entrada y ABI seleccionadas.
   La pestaña anterior **IR del proyecto** conserva sus vistas didácticas y su
   proyección física de referencia Linux; no depende del selector nativo.
4. **Exportar ASM…** guarda el programa NASM completo. **Exportar IR destino…**
   guarda la proyección física correspondiente. Las vistas parciales de cuerpos,
   datos/puentes y adaptador son ayudas de lectura; no se ensamblan por separado.
5. **Ensamblar…** permite elegir una carpeta existente y crea dentro una
   subcarpeta `Programa-<destino>-<marca de tiempo>` con el `.o`/`.obj`.
   **Construir ejecutable…** crea otra subcarpeta y hace ensamblado,
   compilación del runtime, biblioteca estática y enlace. El botón del selector
   es **Guardar aquí**. No modifica fuentes ni sustituye resultados anteriores.
6. **Construcción** se abre automáticamente al comenzar y muestra la carpeta
   de resultados, las etapas, herramientas y sus diagnósticos.
   **Ejecutar** se habilita tras el enlace si el destino corresponde al anfitrión
   x86-64. Para Windows desde Linux se permite construir con herramientas
   cruzadas, pero la GUI no intenta ejecutar ese `.exe`.
7. **Ejecutar** abre **Consola**, con **Entrada interactiva** activada inicialmente.
   La salida y los errores se muestran conforme llegan, incluso preguntas sin
   salto de línea. Escribe una respuesta y pulsa **Enviar** o **Ctrl+Enter**;
   cada envío añade un salto final si falta. Puedes enviar líneas vacías o varias
   líneas juntas. La entrada permanece abierta entre respuestas; **Cerrar entrada**
   envía EOF después de los datos pendientes. El texto escrito antes de ejecutar
   también se envía, añadiendo un salto final si falta, y se limpia el campo.
8. Para pruebas con datos preparados, desactiva **Entrada interactiva**: se envía
   exactamente el texto del campo una vez y se cierra la entrada, como antes.
   Al terminar se muestran stdout y stderr completos por separado y el código
   de salida; si hubo errores, se abre su pestaña. Una lectura escalar con EOF
   explícito o sin datos suficientes en modo preparado sigue siendo un error.
   Esta consola admite entrada por líneas; no emula una terminal con control
   de cursor, colores ANSI o captura de teclas individuales.

Los indicadores separan **IR verificada**, **ASM generado**, **objeto ensamblado**,
**enlace realizado** y **ejecución con código de salida**. Ejecutar con código 0
no significa haber comparado automáticamente la salida con un oráculo.

## Herramientas y distribución

El JAR incorpora los fuentes C, headers y licencias del runtime; la construcción
GUI usa Java y no necesita Python ni el checkout del proyecto. Los ejecutables
resultantes usan el runtime nativo y no requieren JVM. La aplicación compiladora
requiere Java 11 o posterior, como el JAR vigente antes de H6.

**Herramientas…** configura, por destino y durante la sesión, rutas o nombres de
NASM, compilador C, archivador `ar` y plazo por herramienta (120 s iniciales).
Cada campo identifica un único programa; no admite comandos con flags ni shell.
Los caminos con espacios se transmiten como un argumento.

- Linux: NASM, GCC compatible con x86-64 Linux y GNU `ar`.
- Windows: NASM y MinGW-w64 x86-64 con **UCRT**, más su `ar` compatible. No se
  soporta el toolchain MSVC en esta versión.
- Desde Linux a Windows: configura las herramientas cruzadas de MinGW UCRT.
- Valores iniciales: `NASM`, `CC`/`AR`, `CC_WINDOWS`/`AR_WINDOWS`, o nombres
  habituales de las herramientas. El NASM local de `build/tools/nasm` también
  se detecta junto al proyecto y junto al JAR del escritorio cuando el proyecto
  conserva su carpeta `analizadorLexSint`. NASM no se incorpora como binario al JAR.

El builder comprueba `-dumpmachine` y la presencia de `_UCRT` en los headers
Windows antes de compilar el runtime. Usa `elf64`/`win64`, respectivamente, y
mantiene los flags de punto flotante del runtime H4.

Una construcción completa contiene:

```text
programa.asm
programa.ir
programa.tipada.ir
programa.o o programa.obj
programa o programa.exe
identidad-programa.txt
runtime/
  src/, include/, licencias/openjdk11/
  objetos del runtime y libanalizador_runtime.a
  identidad-runtime.txt
```

La identidad guarda destino, clase de entrada y SHA-256 del ASM y de cada
fuente analizada. Identifica también fuentes sin guardar; no copia su texto.
La carpeta se publica únicamente cuando la etapa solicitada ha terminado.

## Invalidación y supervisión

Editar, cambiar archivo/imports o iniciar otro análisis invalida la vista y
cancela su trabajador. Cambiar destino o entrada invalida las salidas nativas y
regenera desde el mismo módulo tipado. Los callbacks tienen una versión: un
trabajador anterior no puede reactivar acciones ni publicar resultados en la GUI.
Las dependencias del disco se comparan con las fuentes analizadas antes de
generar, exportar, construir y ejecutar, y antes de publicar una construcción.
Si cambiaron o desaparecieron, se pide reanalizar y se invalidan las vistas.

Generación, exportación, herramientas y ejecución trabajan fuera del hilo Swing.
Los procesos reciben argumentos separados, tres canales independientes, plazo y
cancelación. Se terminan el proceso y los descendientes detectados; no se ejecuta
un shell. La ejecución tiene 300 s iniciales y un plazo total configurable,
incluido el tiempo de respuesta del usuario. La escritura interactiva usa una
cola de hasta 1 MiB pendiente; enviar y cerrar entrada nunca esperan a que el
programa lea. La captura se limita a 1 MiB por canal; excederlo detiene la operación con diagnóstico,
sin presentar una ejecución completada. La decodificación en vivo conserva
secuencias UTF-8 divididas entre lecturas. Los fallos por plazo conservan la salida
parcial disponible; cancelación, invalidación y terminación cierran la entrada,
y los callbacks de otra ejecución no alteran la consola actual. Cancelar cancela la ejecución y conserva los artefactos ya
publicados; una construcción incompleta se elimina. Las exportaciones usan un
archivo temporal y comprueban vigencia/protección de fuentes antes de sustituir
el destino confirmado.

## Validación y alcance pendiente

- `PruebasH6`, integrada en `PruebasRegresion`: instantánea, documento nuevo,
  entradas múltiples, perfiles, exportación exacta, protección de fuentes/alias,
  integración de pestañas, selección de destino, invalidación y callbacks tardíos,
  imports modificados y errores de frontend.
- `PruebasH6Nativas`: herramientas reales, rutas con espacios/Unicode,
  fuente sin guardar, stdin/stdout/stderr UTF-8, resultados esperados independientes,
  errores RC-1 y visualización GUI, pulsación real de ambos botones con selección
  de una carpeta existente, resultados separados sin sobrescribir fuentes,
  progreso/errores visibles y cancelación del selector. También verifica ensamblado
  sin enlace, fallo de herramienta, no publicación tras cancelación, plazo,
  límite de captura y pipes concurrentes.
  Opcionalmente enlaza PE x64 con MinGW UCRT y comprueba su identidad.
- `PruebasConsolaInteractiva`, incluida en el harness nativo: preguntas visibles
  antes de enviar respuestas, Unicode fragmentado en ambos canales, cierre EOF
  explícito, drenaje de entrada pendiente, cola acotada, plazo/cancelación,
  botones Enviar/Cerrar entrada, Ctrl+Enter, línea vacía, reinicio, invalidación,
  captura final exacta y compatibilidad del modo preparado.
- Validación del JAR aislado: el harness nativo funciona desde una carpeta sin
  checkout ni recursos externos del runtime.
- Regresión completa del frontend, IR, didáctica y H0–H5 aprobada.
- Vista Swing revisada mediante renderizado headless al ancho mínimo de uso.

Comandos de prueba (JDK 11+):

```sh
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasH6Nativas /ruta/nasm gcc ar
# Comprobación cruzada adicional, desde Linux:
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasH6Nativas /ruta/nasm gcc ar /ruta/x86_64-w64-mingw32-gcc /ruta/x86_64-w64-mingw32-ar
```

La implementación H6 está integrada y probada automáticamente en Linux. Su
aceptación manual de diálogos/ventana y la aceptación sobre **Windows real**
continúan pendientes. El enlace cruzado y las pruebas Wine previas de H5 no
sustituyen esa aceptación: consola Unicode, herramientas, desenrollado y stack
probe se deben comprobar en el sistema Windows.
