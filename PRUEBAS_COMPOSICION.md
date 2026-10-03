# Suite de composición semántica

`untitled/src/pruebas/PruebasComposicionSemantica.java` comprueba combinaciones de reglas
de `GRAMATICA_CANONICA.md` usando `CONTRATO_SEMANTICO.md` como oráculo.
Está integrada en `PruebasRegresion` y también se ejecuta por separado.
No requiere JUnit, dependencias adicionales ni pantalla.

## Alcance y cobertura

Contiene **110 casos: 42 válidos y 68 semánticamente inválidos**. Todos deben
pasar el lexer y el parser antes de comprobar su resultado semántico. Una
entrada con un error léxico o sintáctico se informa como un error de la prueba;
no se cuenta como rechazo semántico satisfactorio.

| Grupo | Casos | Producciones relacionadas | Composiciones comprobadas |
| --- | ---: | --- | --- |
| Listas contextuales | 35 | T.2, S.4-S.8, R.1-R.2, E.15, E.18-E.19 | Profundidades 1, 2 y 3; literales agrupados; promoción `int` a `float` en campos, declaraciones, asignaciones, retornos y argumentos; vacíos, `null`, inferencia y llamadas dentro de listas. |
| Invariancia | 12 | T.2, S.4-S.8, R.1, E.18-E.19 | Listas existentes de `int` no se convierten a listas de `float`, tampoco anidadas, en declaración, asignación, argumento o retorno. |
| Listas y herencia | 8 | C.1, C.3, T.1-T.2, CL.3, E.15 | Clase base común, tipos declarados después, literales de subtipos, invariancia de listas de clases, conversión del iterador y ciclo de herencia de tres clases. |
| Sobrecargas y herencia | 16 | C.5-C.10, E.16-E.19 | Firma más específica, tipo estático del argumento, `null`, parámetros cruzados, listas ambiguas, override covariante, despacho virtual, visibilidad y firmas repetidas. |
| Flujo y ámbitos | 22 | I.1-I.2, CL.1-CL.3, I.3, R.1 | Inicialización en ramas anidadas, ramas que retornan, `else if`, cuatro controles que pueden ejecutarse cero veces, retornos contextuales y alcance de parámetros, locales e iteradores. |
| Expresiones y destinos | 12 | S.6-S.8, E.1-E.19 | Precedencia de `not`, llamadas encadenadas, acceso a campos y listas, destinos de asignación, índices, operadores compuestos, contenido de `let`, privacidad y construcción inválida. |
| Diagnósticos y salida | 5 | I.1-I.2, R.1, E.15-E.19 | Acumulación de errores independientes, ausencia de cascadas, efectos de código inalcanzable, conversiones y metadatos de operación y llamada. |

La matriz principal de listas usa el mismo contenido en los cinco destinos:

```text
list<float>             [1, 2.5]
list<list<float>>       [[1, 2.5]]
list<list<list<float>>> [[[1, 2.5]]]
```

Cada combinación válida exige una conversión explícita del elemento entero.
Cada combinación tiene un caso negativo con `true` como elemento incompatible.
Los literales se contrastan además con variables de lista existentes para
comprobar que la promoción contextual no viole la invariancia.

## Comprobaciones de cada caso

- Un caso válido exige cero diagnósticos, éxito y un AST validado disponible.
- Un caso inválido exige la cantidad exacta de diagnósticos esperados y una
  asociación uno a uno de sus mensajes; un error adicional o una cascada falla.
- Los diagnósticos deben ser semánticos y tener línea y columna positivas.
- Un resultado inválido conserva árbol de diagnóstico y ámbitos, pero no
  entrega el AST validado.
- El análisis no debe modificar el árbol sintáctico original.
- En resultados válidos se comprueban tipos de expresiones, ámbitos y enlaces
  por identidad al inventario persistente de símbolos, incluidos los nodos
  adicionales y conversiones del árbol semántico.
- Los casos de sobrecargas inspeccionan los parámetros y la clase declaradora
  de la firma seleccionada. Los casos correspondientes comprueban receptor,
  despacho y promociones, además de la aceptación del programa.
- Cada caso se analiza dos veces sobre el mismo AST. La salida y los
  diagnósticos deben ser reproducibles. Una misma instancia del motor procesa
  todos los casos, comprobando también la limpieza entre programas diferentes.

Los casos se ejecutan completos aunque uno falle. Un fallo muestra el grupo,
el nombre, la comprobación incumplida y la fuente completa. Al final, cualquier
fallo provoca `AssertionError` y una salida de proceso distinta de cero.

## Ejecución

Desde la raíz del proyecto, con **JDK 11 o posterior**:

```sh
mkdir -p build/pruebas
mkdir -p build/pruebas
find untitled/src -name '*.java' -print > build/fuentes.txt
javac --release 11 -encoding UTF-8 -d build/pruebas @build/fuentes.txt
java -Djava.awt.headless=true -cp build/pruebas PruebasComposicionSemantica
java -Djava.awt.headless=true -cp build/pruebas PruebasRegresion
python3 tools/verificar_gramatica.py
```

La prueba no depende de activar las aserciones de Java con `-ea`: utiliza
comprobaciones explícitas. Si `java -version` o `javac -version` muestran Java 8,
selecciona un JDK más reciente para ambos comandos.

Resultado de la ejecución inicial: **110/110 aprobados**, compilando con JDK 25
para Java 11 mediante `--release 11`.

## Límites

Esta suite cubre composiciones concretas del lenguaje vigente; no demuestra
corrección universal ni cobertura exhaustiva de todas sus producciones.
La conformidad general sigue cubierta por `PruebasConformidadGramatica` y la
comparación documental de `tools/verificar_gramatica.py`.

La recuperación ante entradas malformadas, límites de profundidad o tamaño,
imports multiarchivo y pruebas manuales de interfaz pertenecen a otras suites
existentes o a etapas posteriores. No se agregan construcciones ajenas a la
gramática, como `for`, `break`, constructores explícitos o sufijos después de
`new Clase()` y de primarios agrupados.
