# Entradas malformadas y recuperación

`untitled/src/pruebas/PruebasEntradasMalformadas.java` ejercita el flujo léxico,
sintáctico y semántico parcial de entradas dañadas según
`GRAMATICA_CANONICA.md` y `CONTRATO_SEMANTICO.md`.
Está integrada en `PruebasRegresion`; puede ejecutarse de forma independiente.
No requiere bibliotecas adicionales ni una pantalla.

## Cobertura: 759 entradas

| Grupo | Entradas | Qué comprueba |
| --- | ---: | --- |
| EOF y estructura | 28 | Archivo vacío, comentarios sin programa, bloque global y encabezados incompletos, imports, clases, métodos y tipos truncados. |
| Errores léxicos | 15 | Caracteres inválidos, operadores incompletos, identificadores numéricos, decimales y cadenas sin cierre. |
| Recuperación de sentencias | 37 | Expresiones faltantes, separadores, delimitadores, listas, llamadas, destinos, cabeceras de control y ramas `else` huérfanas. Exige conservar la declaración posterior y detectar un error semántico independiente. |
| Recuperación de miembros | 8 | Campos, modificadores y métodos dañados antes de un `main` válido. Exige recuperar su declaración local y su error semántico. |
| Recuperación de imports | 5 | Rutas y terminadores dañados antes de un programa recuperable. |
| Controles válidos | 4 | Programas originales con listas anidadas, llamadas y accesos, ciclos, herencia, override y firmas. Deben producir cero errores. |
| Truncamientos por token | 277 | Todos los prefijos propios de los cuatro programas originales. Deben terminar con diagnósticos sintácticos. |
| Eliminaciones por token | 277 | Cada programa original con un token eliminado. Se comprueba terminación y coherencia del resultado, sin asumir que toda eliminación vuelve inválido un programa. |
| Ruido reproducible | 100 | Secuencias de tokens canónicos y caracteres inválidos, sueltas o dentro de un método. Semilla fija `20260929`. |
| Finales de línea | 6 | Cadenas abiertas y recuperación con LF, CR y CRLF. |
| Anidamiento acotado | 2 | 32 agrupaciones válidas y una variante sin el cierre exterior. |

La generación de casos es determinista y no consulta al motor semántico para
establecer las expectativas. Los casos dirigidos tienen un rechazo léxico o
sintáctico obligatorio. En las eliminaciones y el ruido se permite que una
entrada resulte válida o tenga errores de otra fase; la suite informa las
fases realmente observadas.

## Protección ante bloqueos y excepciones

El supervisor ejecuta los casos en **JVM independientes por lotes de hasta 48**.
Cada caso debe responder dentro de **5 segundos**. El primer plazo incluye el
arranque de la JVM. Los procesos usan el mismo Java y classpath del supervisor.

- Un bloqueo provoca la terminación forzada del proceso y el registro del caso.
- Una excepción o `Error`, incluido `StackOverflowError`, se considera fallo;
  el proceso termina y los siguientes casos continúan en uno nuevo.
- La memoria del proceso de prueba está acotada a `-Xmx128m` y la pila a
  `-Xss1m`. No se cambia la configuración de la aplicación.
- La salida de error se redirige a un archivo para evitar que una tubería llena
  bloquee al proceso. El lector del supervisor también tiene un plazo.
- Los procesos y archivos temporales de los lotes se limpian al terminar.

El supervisor muestra todos los fallos con nombre, grupo, causa y fuente.
Cualquier fallo termina la suite con `AssertionError` y código de salida no
cero. Una excepción no se transforma en una aprobación.

## Validaciones del análisis

Cada entrada comprueba:

1. Un único token EOF y reanálisis léxico reproducible, sin acumular tokens.
2. Diagnósticos con línea y columna positivas.
3. Detención de parser y semántico en el flujo de proyecto si hay error léxico.
4. Marca de sintaxis incompleta y ausencia de AST validado ante error sintáctico.
5. Recuperación de una declaración posterior y de un error semántico independiente
   en los casos dirigidos de sentencias, miembros e imports.
6. Reanálisis reproducible del parser y del motor semántico sobre entradas dañadas.
7. Construcción de las vistas Swing de ambos árboles sin excepciones.
8. Coherencia de éxito y AST validado en `Main.analizarProyecto`, con imports
   locales desactivados para aislar la entrada y evitar selectores de archivos.
9. Conservación del conteo de diagnósticos en el reporte y el modelo de errores
   de la interfaz.

## Fallos encontrados y corregidos

La ejecución inicial aprobó 755 de 757 entradas y detectó:

- Un `else { }` huérfano hacía que su llave de cierre se interpretara como el
  final del método. La declaración posterior se perdía en la recuperación.
  El parser ahora registra el error y consume el bloque o `else if` asociado,
  preservando las sentencias siguientes.
- Los nodos `ERROR` se construían sin ubicación. Después de un retorno, uno
  podía generar un diagnóstico semántico con línea y columna `-1`. Esos nodos
  ahora conservan las coordenadas del token de origen.

Se agregaron dos variantes dirigidas: `else if` huérfano y `else` sin bloque.
El resultado final es **759/759 aprobadas**, sin bloqueos ni excepciones del
motor, compilando con JDK 25 para Java 11 mediante `--release 11`.
La distribución observada es: 628 rechazos sintácticos, 106 léxicos,
18 semánticos y 7 entradas válidas.

## Ejecución

Desde la raíz del proyecto, con **JDK 11 o posterior**:

```sh
mkdir -p build/pruebas
mkdir -p build/pruebas
find untitled/src -name '*.java' -print > build/fuentes.txt
javac --release 11 -encoding UTF-8 -d build/pruebas @build/fuentes.txt
java -Djava.awt.headless=true -cp build/pruebas PruebasEntradasMalformadas
java -Djava.awt.headless=true -cp build/pruebas PruebasRegresion
python3 tools/verificar_gramatica.py
```

El supervisor necesita permiso para iniciar procesos locales de Java y escribir
en el directorio temporal. No requiere red. Las comprobaciones no dependen de
`-ea`. Debe usarse un JDK reciente si el Java predeterminado del equipo es 8.

## Límites

Los tiempos y recursos anteriores son condiciones de esta suite, no límites
implementados en el producto. No se garantiza aceptación de entradas de tamaño
o anidamiento ilimitados. Esta prueba cubre fuentes acotadas y mutaciones de
cuatro programas; no demuestra ausencia de bloqueos para cualquier entrada.

Se verifica la recuperación por los resultados del motor y los modelos Swing.
La revisión visual de la ventana y su comportamiento al abrir archivos mientras
hay un análisis activo sigue siendo una prueba manual pendiente.
