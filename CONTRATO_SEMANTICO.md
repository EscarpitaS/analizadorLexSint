# Contrato del motor semántico

Este contrato complementa `GRAMATICA_CANONICA.md`. Describe el lenguaje del
proyecto, no Java completo. El motor no genera código intermedio.

## Declaraciones y nombres

El análisis registra primero todas las clases, sus bases y las firmas de sus
miembros; después verifica inicializadores y cuerpos. Se permiten referencias
a clases y métodos declarados más adelante. Las clases, valores y métodos
usan espacios de nombres separados. Una sobrecarga se distingue por sus tipos
de parámetros, nunca por el retorno o los nombres de los parámetros.

Se rechazan clases repetidas, campos repetidos en una clase, firmas repetidas,
parámetros repetidos y tipos inexistentes. Los bloques crean ámbitos. Una local
puede ocultar un campo, pero no otra local, parámetro o iterador aún visible.
Los nombres locales dejan de existir al salir de su bloque.

Con `Validar imports locales`, las unidades alcanzables se cargan una sola vez
y comparten el registro de clases. Los imports cíclicos no implican por sí
mismos una herencia cíclica. Cada diagnóstico mantiene su archivo de origen;
un error en una dependencia se propaga al estado del archivo que la importa.
Cada archivo conserva los requisitos sintácticos vigentes de bloque global,
clases y `public main()`. No se cargan firmas de bibliotecas Java externas:
una ruta externa no crea automáticamente tipos o métodos en el lenguaje.

## Tipos y expresiones

Los tipos son `int`, `float`, `string`, `bool`, `list<T>` y clases declaradas.
`void` solo representa retorno sin valor. Se admite promoción de `int` a
`float`, asignación de una clase derivada a una base y `null` a referencias
(`string`, listas y clases). No hay conversiones implícitas inversas.

`+`, `-`, `*` y `/` operan sobre números. `+` también concatena cuando alguno
de sus operandos es `string`, siempre que el otro produzca un valor.
`not`, `&&` y `||` requieren booleanos; el menos unario requiere un número.
Las comparaciones de orden requieren números. La igualdad admite números,
booleanos, referencias compatibles, referencias con `null` y `null == null`.
Cada incompatibilidad informa un error, además de producir el tipo interno de
error para limitar diagnósticos derivados.

`var` y `let` requieren un tipo inferible: `null`, `void` o listas sin un tipo
de elemento deducible no bastan. `let` impide reasignar la variable; no vuelve
inmutable el contenido de un objeto o lista referenciada.

Solo una llamada puede usarse como sentencia de expresión. Una asignación
requiere una variable, un campo accesible o un elemento de lista como destino.
La asignación simple no lee el destino; las asignaciones compuestas sí lo leen
y verifican también el tipo del resultado. Un índice debe ser `int`.

## Listas

Las variables de lista son invariantes: `list<int>` no es asignable a
`list<float>`, ni `list<Hija>` a `list<Base>`. Un literal sí se comprueba
contra el tipo esperado en declaraciones, campos, asignaciones, retornos y
argumentos; por ejemplo, `list<float> xs = [1]` es válido. Esto también se
aplica a listas anidadas y a `[]`.

Sin contexto, un literal obtiene un tipo común para sus elementos: igualdad
de tipos, promoción numérica, compatibilidad con `null` o una clase base común.
Los elementos incompatibles y las expresiones sin valor se rechazan.
`var xs = []` y `var xs = [null]` no permiten inferir un tipo utilizable.

## Herencia, visibilidad y llamadas

Se rechaza una base inexistente, la herencia propia y los ciclos de herencia.
Las relaciones inválidas se cortan para permitir que termine el diagnóstico.
Los miembros sin modificador se consideran `private`; `public` permite acceso
desde otras clases. La privacidad de un miembro se determina por su clase
declaradora, no por el tipo de la variable usada como receptor. Los nombres de
clases registrados están disponibles en el ámbito global del programa.

Los campos y métodos públicos de las bases se pueden resolver desde la clase
derivada. Los métodos privados no participan en la sobrescritura. Una
sobrescritura pública no puede reducir la visibilidad y debe conservar su tipo
de retorno, salvo retorno covariante entre clases. La promoción numérica no
cambia la firma de retorno de una sobrescritura.

Una llamada considera firmas accesibles y compatibles en aridad y tipos. Se
elige la única firma más específica: sus parámetros deben ser asignables a
los de las otras candidatas, con al menos una diferencia estricta. Si no hay
una única mejor firma, se informa ambigüedad. Las listas siguen siendo
invariantes en esta comparación; dos sobrecargas de lista compatibles con un
literal pueden resultar ambiguas. Una firma sobrescrita no se cuenta dos veces.

Todas las clases tienen únicamente constructor implícito sin parámetros:
`new Clase()`. No se admiten argumentos ni constructores explícitos. Un método
que se llame igual que su clase sigue siendo un método ordinario.

## Inicialización y flujo

Los campos tienen valor inicial por defecto; los parámetros y los iteradores
están inicializados al entrar en sus cuerpos. Las variables locales deben
estar definitivamente inicializadas antes de leerse, incluido su propio
inicializador.

Después de `if/else`, una variable debe estar inicializada en todas las ramas
que pueden continuar. Una rama que retorna no participa en esa intersección.
Los ciclos y `Exist` se consideran conservadoramente capaces de ejecutar cero
veces, incluso si su condición es un literal: no garantizan inicialización ni
retorno fuera del cuerpo.

`if`, `while` y `repeat (condición)` exigen `bool`. `repeat each` exige una
lista cuyos elementos sean asignables al tipo del iterador. `Exist` exige una
lista. El iterador solo existe dentro de su cuerpo.

Un método con retorno distinto de `void` debe retornar por todos los caminos.
`return;` solo es válido en métodos `void`; `return expresión;` exige un método
con valor de retorno compatible. Se rechazan sentencias después de un retorno
garantizado, incluso cuando lo garantizan ambas ramas de un `if/else`.

## Verificación y alcance de la presentación

`PruebasRegresion` incluye `PruebasSemanticas`: casos válidos e inválidos,
reutilización del motor y análisis de imports locales con diagnósticos por
archivo. Puede ejecutarse `PruebasSemanticas` por separado.

La interfaz y los reportes incluyen árbol sintáctico con acciones y árbol
semántico con tipos y acciones. Las acciones se registran durante las pasadas:
declaración de símbolos, resolución de nombres y llamadas, apertura de ámbitos,
comprobaciones de tipos y diagnósticos.

Con errores sintácticos, el parser marca las construcciones incompletas. El
semántico omite esas construcciones y analiza las partes recuperadas; no exige
retorno completo en un cuerpo con sintaxis dañada. Una declaración local dañada
puede reservar su nombre con tipo de error para evitar diagnósticos derivados.
La vista identifica los fragmentos omitidos y el reporte indica que el análisis
es parcial. No se afirma validez semántica del código omitido.

Los errores léxicos siguen impidiendo el parser y el semántico de esa unidad.
Los diagnósticos sintácticos y semánticos disponibles se combinan por ubicación,
conservando su fase. Se muestra un estado explícito para cada combinación.
`PruebasPresentacionSemantica` verifica estas salidas y el modelo de árbol Swing,
y forma parte de `PruebasRegresion`.

## Contrato de salida para fases posteriores

`Semantico.analizar` devuelve `ResultadoSemantico`. El resultado contiene
`esExitoso()`, `getErrores()`, `getAmbitoGlobal()`, `getArbolDiagnostico()` y
`getAstValidado()`. El último devuelve `null` ante fallo. En el flujo del proyecto,
`Main.ResultadoAnalisis.resultadoSemantico` incorpora además errores léxicos,
sintácticos, imports y el estado global de las dependencias.

Un consumidor debe recorrer `getHijosSemanticos()` para ver conversiones
implícitas como nodos. `getHijos()` conserva el árbol sintáctico original. Cada
conversión conserva el operando con su tipo fuente y su propio tipo destino.
No es necesario resolver nuevamente nombres: `getSimboloResuelto()` entrega la
misma instancia que consta en el árbol persistente de ámbitos. `getAmbitoResuelto()`
entrega el ámbito del nodo. Los ámbitos exponen `getSimbolos()` y `getHijos()`.

Las llamadas conservan el símbolo de la firma elegida, el tipo estático del
receptor y el receptor explícito cuando existe. Un receptor nulo en una llamada
indica receptor implícito. Métodos privados usan despacho `DIRECTO`; métodos
públicos, `VIRTUAL`. Esto no pretende conocer en compilación la clase concreta de
un receptor polimórfico. `new` conserva clase y símbolo del constructor implícito
con despacho `CONSTRUCTOR`.

Las asignaciones compuestas mantienen su destino sintáctico y `getTipoOperacion()`;
las aristas semánticas indican promociones para la lectura y operación. En
`repeat each`, una conversión del elemento a tipo del iterador aparece sobre un
nodo `ELEMENTO_ITERADO`, sin convertir la lista mutable completa.

Las sentencias inalcanzables se siguen comprobando y llevan marca explícita.
Sus efectos de inicialización no alteran el estado de las rutas alcanzables.
Los fragmentos sintácticamente inválidos conservan su tratamiento de omisión.

Antes de declarar éxito, se verifican ámbitos, tipos de expresiones y enlaces
esenciales del AST decorado. Los consumidores de generación solo deben aceptar
el AST validado; el árbol de diagnóstico puede contener datos parciales. Reanalizar
un AST reemplaza sus decoraciones: debe consumirse con el resultado y la tabla de
ámbitos de su análisis más reciente.
