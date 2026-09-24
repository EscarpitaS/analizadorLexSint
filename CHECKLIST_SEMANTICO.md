# Auditoría del checklist semántico — 2026-09-22

Resultado inicial de la auditoría: incompleto para entregar un AST directamente
consumible por un generador de código intermedio. Los huecos de esa auditoría
fueron corregidos y verificados en la sección de cierre de este documento.

## Condiciones globales

| Condición | Estado | Evidencia |
| --- | --- | --- |
| Cobertura de construcciones con significado semántico | Parcial | Hay recorridos para declaraciones, control, expresiones, llamadas, herencia y construcción. Faltan decoraciones completas y una comprobación exhaustiva de nodos; `calcularTipo` tiene un recorrido genérico que devuelve el último tipo. |
| Cierre bajo composición | No cumplido | La lista `[[1], [2.5]]` es válida como inicializador `list<list<float>>`, pero se rechaza como argumento de ese mismo tipo. |
| El generador no debe resolver cuestiones semánticas | No cumplido | No se conservan referencias a símbolos en nodos, coerciones explícitas, destino estructurado de llamadas ni política de despacho. |

Una producción auxiliar de la BNF, como una cola de lista o una alternativa
vacía, no necesita un método `visit` propio si desaparece al construir el AST.
Sí debe tener responsable semántico cada construcción representada en el AST.

## Checklist detallado

| Requisito | Estado | Observación |
| --- | --- | --- |
| Toda declaración en tabla con tipo resuelto | Parcial | Se crean símbolos, pero ámbitos de métodos y bloques no se retienen como resultado consultable. La tabla visible procede del parser. |
| Shadowing por nivel | Cumplido en los casos verificados | Se permite ocultar campos, no locales/parámetros/iteradores aún visibles. |
| Uso antes de declaración | Cumplido según contrato | Locales: error. Clases y firmas: referencias hacia adelante permitidas mediante primera pasada. Campos: registrados previamente y con valor por defecto. |
| Identificadores enlazados al símbolo correcto | Pendiente | `NodoArbolSintactico` conserva nombre, tipo y textos de acciones, no `Simbolo`. |
| Cada expresión anotada con tipo | Parcial | Las expresiones evaluadas reciben tipos. Falta validación exhaustiva del AST exitoso y metadatos en identificadores de construcción, parámetros e iteradores. No todos estos identificadores son expresiones, pero son relevantes para la siguiente fase. |
| Reglas de operadores y promoción | Parcial | Las familias de operadores están implementadas; la propagación contextual de promociones no es uniforme en composiciones. |
| Conversiones implícitas como nodos | Pendiente | `float x = 1` se acepta sin insertar una conversión explícita en el AST. |
| Tipo centinela de error | Cumplido | Existe `ERROR`. Su uso todavía no evita todas las cascadas. |
| Aridad/tipos de argumentos | Parcial | Se comprueban firmas y sobrecargas, pero falla la composición de listas anidadas con conversión contextual. |
| Construcción `new` | Cumplido para el lenguaje | Solo constructor implícito sin parámetros. Falta conservar enlace estructurado a clase/constructor en la salida. |
| Resolución/despacho polimórfico | Pendiente en la salida | Se selecciona firma usando el tipo estático. El método elegido solo se describe como texto; no se conserva símbolo ni clasificación de despacho. |
| Jerarquía sin ciclos | Cumplido en los casos verificados | Se detectan y cortan ciclos; se informa base inexistente/herencia propia. |
| Visibilidad de miembros heredados | Cumplido en los casos verificados | Se considera la clase declaradora y se excluyen métodos privados heredados. |
| Override | Cumplido en los casos verificados | Firma, retorno covariante entre clases y prohibición de reducir visibilidad. |
| Clases abstractas | No aplica | No pertenecen a la gramática. |
| Retorno por todas las rutas | Parcial frente al checklist estricto | `if/else` y bloques se recorren; ciclos y `Exist` se modelan como capaces de ejecutar cero veces. Es una regla conservadora documentada, no análisis de constantes ni CFG explícito. |
| Inicialización antes de lectura | Cumplido en los casos verificados | Estado por ámbito y combinación de ramas que continúan. No constituye prueba universal de composición. |
| Código inalcanzable | Cumplido como detección | Se informa como error después de retorno garantizado; después se abandona ese bloque. |
| Todos los errores se acumulan sin detener el recorrido | Parcial | Se acumulan muchos errores, pero el `break` tras retorno omite diagnósticos independientes del resto del bloque. |
| Ausencia de cascadas | No cumplido | `var x = falta;` produce identificador inexistente y fallo de inferencia derivado del mismo error. |
| Ubicación y contexto de diagnósticos | Cumplido en los casos verificados | Línea, columna, lexema, mensaje y sugerencia; el análisis de unidades separa errores por archivo. |
| AST decorado completo | Pendiente | Faltan enlaces a símbolos, conversiones y metadatos de llamada/construcción. |
| Tabla semántica completa y consultable | Pendiente | No se exporta el árbol de ámbitos ni el inventario persistente de locales y parámetros. |
| Resultado `exitoso + errores + AST validado` | Pendiente | Hay `hasErrors()` y resultados parciales, pero ninguna API que entregue un AST garantizado exclusivamente al tener éxito global. |

`for`, `switch`, `try`, `break`, clases abstractas y métodos abstractos no existen
en esta gramática. No se deben añadir para satisfacer una lista genérica.
Offsets y tamaños físicos necesitan un modelo de ejecución/ABI; normalmente
son responsabilidad de layout o de generación. No hacen falta todavía para
cerrar el análisis de nombres y tipos. Sí hacen falta identidad de símbolos,
roles, propietarios, ámbitos, firmas y tipos estables.

## Reproducciones ejecutadas

Las siguientes entradas llegaron sin errores léxicos ni sintácticos:

1. Herencia + override + while + if/else + retornos + llamada mediante referencia
   base: aceptado.
2. `list<list<float>> xs = [[1], [2.5]];`: aceptado.
3. `void f(list<list<float>> xs) { }` y `f([[1], [2.5]]);`: un error de elementos
   incompatibles. Debería aceptarse conforme al contrato de listas contextuales.
4. `var x = falta;`: dos errores, uno de ellos derivado.
5. `return; bool x = 1; int y = falta;`: solo un error de código inalcanzable.
   No se visitan los otros errores independientes.

La suite existente sigue pasando, lo que confirma que no cubría estos huecos.

## Correspondencia de responsabilidades

- Clases, herencia, campos, firmas: `registrarDeclaraciones`, `validarHerencia`,
  `validarSobrescrituras`, `registrarCampo`, `registrarMetodo`.
- Parámetros/cuerpos: `verificarMetodo`, `visitarBloque`.
- Declaraciones/asignaciones: `visitarDeclaracion`, `visitarAsignacion`.
- Condicionales/ciclos/Exist: `visitarControl`, `visitarElse`, `visitarCiclo`,
  `visitarExist`; retornos en `visitarSentencia`, `flujoDe`, `flujoElse`.
- Literales, operadores, agrupación, listas, accesos: `tipoDe`, `calcularTipo`,
  `tipoEsperado`; llamadas y construcción: `tipoLlamada`, `tipoConstruccion`.
- Imports locales: carga en `Main` y registro conjunto en `analizarUnidades`.
- Producciones auxiliares: las absorbe el parser; no precisan visitantes propios.

## Orden de corrección

1. Persistir ámbitos y símbolos y enlazarlos a declaraciones y referencias del AST.
2. Unificar el tipado contextual de listas/argumentos e insertar conversiones explícitas.
3. Conservar resolución de llamadas, receptor y despacho; enlazar construcción a su clase.
4. Corregir propagación de `ERROR` y continuar comprobando código inalcanzable sin
   contaminar el flujo de las rutas alcanzables.
5. Definir una salida validada para el generador, separada del árbol parcial de diagnóstico.
6. Añadir pruebas de composición y un verificador de invariantes del AST exitoso.

## Actualización de implementación — enlaces persistentes y salida validada

La tabla anterior conserva el diagnóstico de partida. Se implementaron los seis
puntos del orden de corrección:

- Los nodos guardan referencias a `Simbolo`, ámbito, clase construida, receptor y
  despacho. Declaraciones y usos comparten la misma identidad de símbolo.
- El ámbito global conserva hijos de clase, método y bloque; cada ámbito expone
  símbolos e hijos mediante vistas de solo lectura. La tabla de la interfaz y
  los reportes se obtiene ahora de estos ámbitos semánticos.
- `getHijosSemanticos()` expone nodos `CONVERSION_IMPLICITA`; `getHijos()` conserva
  la estructura sintáctica. Se materializan promociones en inicializaciones,
  argumentos, retornos, operadores, listas, asignaciones e iteración.
- El recorrido continúa en código inalcanzable, marca los nodos y conserva por
  separado el estado de inicialización de las rutas alcanzables.
- `resolverContexto` comprueba tanto inicializaciones como argumentos y retornos;
  las candidatas de sobrecarga se prueban sin emitir errores ni modificar el AST.
- `ResultadoSemantico` expone éxito, errores, ámbito global y dos accesos distintos:
  árbol diagnóstico y AST validado, nulo ante fallo. La salida de `Main` incluye
  el estado de todas las fases y las dependencias.

Se corrigió también la cascada de inferencia de `var`: una expresión de tipo
`ERROR` propaga ese tipo sin generar un segundo diagnóstico de inferencia.

`PruebasAstDecorado` forma parte de la suite. Comprueba identidad y pertenencia
al árbol de ámbitos, construcción, despacho, conversiones, listas anidadas como
argumentos/retornos, invariancia, ambigüedad, reanálisis sin acumulación y separación
del estado inalcanzable. Las pruebas de presentación/imports comprueban la barrera
de salida ante errores sintácticos, léxicos y de dependencias.

Se mantiene el análisis conservador de ciclos acordado. Esta actualización no
incluye generación de código, offsets físicos, ABI ni una prueba formal de todas
las composiciones del lenguaje. Los árboles son objetos de trabajo: reanalizar el
mismo AST sustituye sus decoraciones; el consumidor debe usar el resultado más
reciente junto con su ámbito global.

## Cierre automático previo a pruebas manuales

El 24 de septiembre de 2026 se ejecutaron por separado:

- `PruebasSemanticas`: 80 casos aprobados.
- `PruebasPresentacionSemantica`: árboles, acciones y diagnósticos combinados aprobados.
- `PruebasConformidadGramatica`: conformidad léxica, sintáctica y semántica aprobada.
- `PruebasAstDecorado`: enlaces, ámbitos, conversiones y resultado validado aprobados.
- `PruebasRegresion`: suite completa aprobada.
- `python3 tools/verificar_gramatica.py`: 53 producciones coincidentes con `Gramatica.java`.

También se ejecutaron los ejemplos incluidos:

- `programa_demo.txt`: `SIN ERRORES`, con ambos árboles y 0 errores.
- `programa_error_sintactico.txt`: `ERRORES SINTACTICOS`, con árbol sintáctico y semántica parcial.
- `programa_con_errores.txt`: `ERRORES LEXICOS`, detenido antes del parser.

Con estas comprobaciones automáticas, el motor queda listo para pruebas manuales
de la interfaz y de los reportes. La revisión manual sigue siendo necesaria para
la legibilidad visual y para validar ejemplos específicos de la entrega.
