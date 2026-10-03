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

1. Ejecuta `Main`; la pestaña `Editor` reúne las acciones de archivo,
   análisis, imports locales, exportación del reporte y contadores. El estado
   del análisis aparece en su barra inferior.
2. Usa `Abrir ejemplo` o `Abrir archivo` para cargar código `.txt`, `.java` o `.cpp`.
   El ejemplo se abre como documento nuevo; `Guardar` permite crear tu copia.
3. Edita el código y pulsa `Analizar` (`F5` o `Ctrl+Enter`). El análisis utiliza
   el contenido del editor, incluidos los cambios sin guardar.
4. Usa `Guardar` (`Ctrl+S`) o `Guardar como…` para guardar el código en UTF-8.
5. Activa `Validar imports locales` solo si los imports son archivos del mismo
   proyecto. Primero guarda un documento nuevo para definir su carpeta; las
   dependencias se leen del disco.
6. Consulta la pestaña `Resultados` y usa `Guardar reporte` para exportarlos.
   En la tabla de errores, haz doble clic o pulsa `Enter` sobre una fila para
   ir a su línea y columna en el editor. Si el error pertenece a una dependencia,
   se abre ese archivo con la confirmación habitual de cambios sin guardar.
   Los diagnósticos sin ubicación no permiten navegar y los resultados de un
   código modificado requieren un nuevo análisis antes de navegar.

El editor incluye números de línea, desplazamiento horizontal sin ajuste de
líneas, resaltado de palabras reservadas, tipos, cadenas, números y comentarios
`//`, y deshacer/rehacer (`Ctrl+Z`, `Ctrl+Y` o `Ctrl+Shift+Z`). `Tab` y `Shift+Tab`
aplican o quitan cuatro espacios de sangría; `Enter` conserva la sangría y la
aumenta después de `{`. `Ctrl+N` crea un documento, `Ctrl+O` abre un archivo y
`Ctrl+Shift+S` guarda con otro nombre. Los finales de línea se normalizan a LF.

Los colores sirven como guía léxica y toleran código incompleto; `Analizar`
comprueba su validez. Editar deja los resultados anteriores marcados como
desactualizados y deshabilita su exportación hasta volver a analizar. Abrir,
crear o cerrar un documento con cambios ofrece guardar, descartar o cancelar.
El resaltado no añade operaciones al historial ni marca cambios sin guardar.

### Autocompletado básico

El menú aparece al escribir al menos dos caracteres de una palabra. También
puedes abrirlo con `Ctrl+Espacio` o con el botón `Completar`, incluso con el
prefijo vacío. Las sugerencias incluyen el vocabulario del lexer y los nombres
visibles del documento: clases, tipos importados, campos, métodos, parámetros,
variables locales e iteradores. Cada opción indica su categoría y, cuando está
declarado, su tipo.

- Usa las flechas arriba/abajo para elegir y `Enter` o `Tab` para insertar.
- Haz clic sobre una opción para aceptarla, o pulsa `Esc` para cerrar el menú.
- La inserción reemplaza la palabra completa, incluso con el cursor en medio,
  y se deshace con una sola operación.
- Las sugerencias respetan las mayúsculas del lenguaje y no aparecen dentro de
  comentarios, cadenas ni después de un punto.

El índice se actualiza desde el texto sin guardar y tolera bloques incompletos.
Las locales se ofrecen después de su declaración y en su ámbito; los campos y
métodos de la clase pueden aparecer aunque se declaren más adelante. Los tipos
importados se sugieren por su nombre. La búsqueda de miembros de un receptor,
la herencia y el filtrado por la producción gramatical esperada requieren una
fase posterior de autocompletado contextual. `F5` continúa siendo la comprobación
léxica, sintáctica y semántica del programa.

`PruebasAutocompletado` verifica ámbitos, prefijos, fragmentos incompletos,
teclado, inserción e historial. Acepta opcionalmente una ruta PNG para renderizar
el editor con el menú de sugerencias.

`PruebasEditorCodigo` comprueba colores, historial, sangría, persistencia y el
análisis del texto en memoria con imports, en modo headless. Acepta como argumento
opcional la ruta de un PNG para renderizar el editor.

Las pestañas **AST sintáctico** y **AST semántico** muestran un diagrama con
ramas plegables, un índice de navegación y los detalles del nodo seleccionado.
El código fuente resalta su línea de inicio (los nodos sin ubicación propia no
se resaltan). Las acciones semánticas se consultan en los detalles; las
conversiones implícitas forman parte del diagrama semántico.

Los estados se muestran con fondo y borde de color, etiqueta e icono en el
índice, y se conservan al seleccionar el nodo. El contorno exterior azul indica
la selección sin sustituir el color del estado:

- **Rojo:** sintaxis incompleta. Los descendientes de ese fragmento muestran
  `Fragmento incompleto`; el inspector distingue el marcador propio del contexto.
  El indicador global `AST parcial` de la raíz no colorea las ramas sanas.
- **Naranja:** código inalcanzable tras un retorno garantizado, en el AST semántico.
- **Rosa:** error semántico asociado al nodo, en el AST semántico. El inspector
  muestra sus mensajes concretos. Si además es inalcanzable, se conserva el
  naranja y los mensajes siguen disponibles en los detalles.

`PruebasEstadosAst` verifica estos colores en casos reales, incluyendo nodos
seleccionados, fragmentos sanos y la limpieza de diagnósticos al repetir el análisis.
Acepta opcionalmente una carpeta para generar capturas de los tres estados.

- Selecciona un nodo en el diagrama o en el índice para consultar su valor,
  ubicación, tipo, símbolo y acciones disponibles.
- Haz doble clic sobre un nodo o pulsa su control `+`/`−` para plegar su rama.
- Usa `+`, `−` o `Ctrl + rueda` para cambiar el zoom, y arrastra el fondo para moverte.
- `Ajustar` muestra una vista global; `Inicio` vuelve a la raíz; `Resumir` pliega
  las ramas profundas; `Expandir todo` muestra todos los nodos.
- `Enfocar rama` aísla el subárbol seleccionado para explorar expresiones o
  declaraciones grandes; `Inicio` recupera el árbol completo.
- Busca por nombre, valor o tipo y pulsa `Enter` o `Siguiente` para recorrer
  coincidencias, incluso dentro de ramas plegadas.
- Con el diagrama enfocado, las flechas izquierda/derecha navegan al padre/primer
  hijo y `Enter` pliega o expande la rama.

La prueba `PruebasVisorAst` comprueba estas interacciones en modo headless.
Recibe opcionalmente una carpeta de salida para generar capturas de revisión.

En IntelliJ, ejecuta la clase `Main` sin argumentos para abrir Swing. Si la
salida muestra que la JVM no tiene entorno gráfico, elimina
`-Djava.awt.headless=true` de la configuración de ejecución; ese parámetro
fuerza el modo consola y evita que Java cree ventanas.

## Estructura y compilación

En `untitled/src/` solo se encuentran los tres archivos del motor:
`Lexico.java`, `AnalizadorSintactico.java` y `Semantico.java`.
Las clases auxiliares se organizan por función:

- `aplicacion/`: punto de entrada `Main` y coordinación del análisis.
- `interfaz/`: ventana Swing, editor, autocompletado y visor AST.
- `modelo/`: tokens, errores, importaciones, nodos AST y resultados.
- `gramatica/`: definición de la gramática.
- `herramientas/`: depuración del lexer.
- `pruebas/`: todas las clases de pruebas.

La organización es por carpetas; las clases conservan el paquete por defecto
y sus nombres. La compilación debe incluir recursivamente todas las fuentes.
Con un JDK 11 o posterior en `PATH` o seleccionado mediante `JAVA_HOME`, ejecuta:

```sh
python3 tools/compilar.py
java -jar build/AnalizadorLexSint.jar
```

El script compila con compatibilidad Java 11 y empaqueta los ejemplos y la
documentación. Las pruebas también están disponibles en el JAR:

```sh
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasVisorAst
```

## Verificación

Compila las fuentes y ejecuta `PruebasRegresion`. La prueba cubre programas
válidos, errores léxicos, sintácticos y semánticos del lenguaje canónico.
Incluye `PruebasSemanticas` y `PruebasComposicionSemantica`, que también pueden
ejecutarse por separado. La cobertura y ejecución de los 110 casos de composición
están en [PRUEBAS_COMPOSICION.md](PRUEBAS_COMPOSICION.md).
También incluye `PruebasEntradasMalformadas`: 759 casos con límites de tiempo
y procesos aislados. Su cobertura y ejecución están en
[PRUEBAS_RECUPERACION.md](PRUEBAS_RECUPERACION.md).
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
