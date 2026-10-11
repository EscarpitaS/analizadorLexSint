# Gramática canónica del lenguaje

Esta es la especificación de referencia para el lexer, el parser, los ejemplos y
el análisis semántico. Sustituye las partes contradictorias de la
documentación original. El lenguaje es pequeño e inspirado en Java; **no**
pretende aceptar Java completo.

## Decisiones de diseño

- Un programa tiene `import` opcionales y un único bloque global con clases.
- El separador de parámetros, argumentos y elementos de lista es `,`. El punto
  (`.`) se reserva para rutas de importación, decimales y acceso a miembros.
- Los tipos primitivos son `int`, `float`, `string` y `bool`. `void` solo es
  retorno de método. `decimal` no es un tipo primitivo del lenguaje canónico.
- Las extensiones `for`, `each` como sentencia independiente, `repeat while`,
  `and`, `or` y el `:` posterior a una condición quedan fuera del lenguaje.
- `Exist` se conserva como construcción propia del lenguaje.
- Un `main` válido tiene la forma `public main() { ... }`; debe existir al
  menos uno en el programa. Esta última cardinalidad se valida como regla
  estructural del parser, no se fuerza dentro de la BNF.

Las reglas completas del motor se detallan en [CONTRATO_SEMANTICO.md](CONTRATO_SEMANTICO.md).

## Contrato léxico

El lexer produce estos terminales. Los espacios y los comentarios de línea
`// ...` se descartan.

| Categoría | Lexemas o forma |
| --- | --- |
| Identificador (`ID`) | `[A-Za-z][A-Za-z0-9]*`; `1nombre` es error léxico |
| Entero (`ENTERO`) | uno o más dígitos |
| Flotante (`FLOAT`) | dígitos `.` dígitos |
| Cadena (`CADENA`) | texto entre comillas dobles en una sola línea |
| Booleano | `true`, `false` |
| Nulo | `null` |
| Palabras clave | `import class extends public private main void return if else while repeat each in Exist new var let int float string bool list not` |
| Delimitadores | `{ } ( ) [ ] ; , .` |
| Operadores | `= += -= *= /= == != < <= > >= + - * / && ||` |

Los identificadores distinguen mayúsculas de minúsculas y usan letras ASCII:
no admiten `_`, letras acentuadas ni otros caracteres Unicode. Las palabras de
la fila «Palabras clave», junto con `true`, `false` y `null`, están reservadas.
Nombres como `for`, `decimal`, `and` y `or` no están reservados: pueden ser
identificadores, pero no activan construcciones ni operadores adicionales.

LF, CRLF y CR delimitan líneas. Una cadena no puede cruzar ninguno de esos
límites y una cadena incompleta no produce un token válido. No hay secuencias
de escape: una comilla doble siempre termina la cadena. Los comentarios `//`
terminan al final de la línea o del archivo; no existen comentarios `/* ... */`.
El signo `-` es un operador separado del número. `1.` es un error léxico porque
falta la parte decimal; `.5` no es un literal flotante de esta gramática.

`!`, `&` y `|` aislados son errores léxicos. El balance de delimitadores, el
bloque global y la presencia de `main` **no** son responsabilidades del lexer.

## Convenciones

- `ε` representa la producción vacía.
- Los literales entre comillas simples son terminales por lexema.
- `ID`, `ENTERO`, `FLOAT` y `CADENA` son categorías de token. En Java,
  `ID` se representa como `IDENTIFICADOR` y `CADENA` como `STRING`.
- La gramática está factorizada para análisis predictivo con un token de
  preanálisis. El parser puede implementarse por descenso recursivo; no es
  obligatorio mantener una tabla LL(1) explícita.

## BNF

### Programa e imports

```bnf
Programa          -> ListaImports BloqueGlobal EOF
ListaImports       -> 'import' Ruta ';' ListaImports | ε
Ruta              -> ID RutaResto
RutaResto         -> '.' ID RutaResto | ε
BloqueGlobal      -> '{' ListaClases '}'
ListaClases       -> Clase ListaClases | ε
```

### Clases, campos y métodos

```bnf
Clase             -> ModificadorOpt 'class' ID HerenciaOpt '{' ListaMiembros '}'
ModificadorOpt    -> 'public' | 'private' | ε
HerenciaOpt       -> 'extends' ID | ε
ListaMiembros     -> Miembro ListaMiembros | ε
Miembro           -> ModificadorOpt MiembroResto
MiembroResto      -> 'main' '(' ')' Bloque
                  | 'void' ID '(' ParametrosOpt ')' Bloque
                  | Tipo ID MiembroTipadoResto
MiembroTipadoResto-> '(' ParametrosOpt ')' Bloque
                  | InicializacionOpt ';'
ParametrosOpt     -> Parametro ListaParametros | ε
ListaParametros   -> ',' Parametro ListaParametros | ε
Parametro         -> Tipo ID
```

Un miembro tipado seguido de `(` es método; seguido de `=` o `;` es campo.
`var` y `let` solo se permiten dentro de bloques, nunca como miembros de clase.

### Tipos

```bnf
Tipo              -> TipoBase | ID
TipoBase          -> 'int' | 'float' | 'string' | 'bool'
                  | 'list' '<' Tipo '>'
```

El `ID` de `Tipo` representa un tipo definido por el usuario. Su existencia y
la validez de la herencia corresponden al análisis semántico.

### Bloques y sentencias

```bnf
Bloque            -> '{' ListaSentencias '}'
ListaSentencias   -> Sentencia ListaSentencias | ε
Sentencia         -> Declaracion
                  | SentIf
                  | SentWhile
                  | SentRepeat
                  | SentExist
                  | SentReturn

Declaracion       -> 'var' ID '=' Expresion ';'
                  | 'let' ID '=' Expresion ';'
                  | TipoBase ID InicializacionOpt ';'
InicializacionOpt -> '=' Expresion | ε

Sentencia         -> ID SentenciaTrasPrimerId
SentenciaTrasPrimerId -> ID InicializacionOpt ';'
                  | OperadorAsignacion Expresion ';'
                  | ';'
                  | Sufijo Sufijos FinalSentenciaId
FinalSentenciaId  -> OperadorAsignacion Expresion ';' | ';'
OperadorAsignacion-> '=' | '+=' | '-=' | '*=' | '/='

SentIf            -> 'if' '(' Expresion ')' Bloque ElseOpt
ElseOpt           -> 'else' (Bloque | SentIf) | ε
SentWhile         -> 'while' '(' Expresion ')' Bloque
SentRepeat        -> 'repeat' RepeatResto
RepeatResto       -> '(' Expresion ')' Bloque
                  | 'each' '(' Tipo ID 'in' Expresion ')' Bloque
SentExist         -> 'Exist' '(' Expresion ')' Bloque
SentReturn        -> 'return' ExpresionOpt ';'
ExpresionOpt      -> Expresion | ε
```

La alternativa que empieza con dos `ID` declara una variable de tipo definido
por el usuario, por ejemplo `Persona p;`. Las demás alternativas permiten
llamadas y accesos como `f();`, `obj.metodo();`, `lista[0] = 3;` y `x += 1;`.
El análisis semántico debe rechazar una expresión aislada que no sea una
llamada, por ejemplo `x;`.

### Expresiones

```bnf
Expresion         -> ExprOr
ExprOr            -> ExprAnd ExprOrResto
ExprOrResto       -> '||' ExprAnd ExprOrResto | ε
ExprAnd           -> ExprNot ExprAndResto
ExprAndResto      -> '&&' ExprNot ExprAndResto | ε
ExprNot           -> 'not' ExprNot | ExprComparacion
ExprComparacion   -> ExprSuma ExprComparacionResto
ExprComparacionResto -> OperadorComparacion ExprSuma | ε
OperadorComparacion -> '==' | '!=' | '<' | '<=' | '>' | '>='
ExprSuma          -> ExprProducto ExprSumaResto
ExprSumaResto     -> ('+' | '-') ExprProducto ExprSumaResto | ε
ExprProducto      -> ExprUnaria ExprProductoResto
ExprProductoResto -> ('*' | '/') ExprUnaria ExprProductoResto | ε
ExprUnaria        -> '-' ExprUnaria | Primario
Primario          -> ENTERO | FLOAT | CADENA | 'true' | 'false' | 'null'
                  | '(' Expresion ')'
                  | 'new' ID '(' ArgumentosOpt ')'
                  | '[' ArgumentosOpt ']'
                  | ID Sufijos
Sufijos           -> Sufijo Sufijos | ε
Sufijo            -> '(' ArgumentosOpt ')'
                  | '[' Expresion ']'
                  | '.' ID
ArgumentosOpt     -> Expresion ListaArgumentos | ε
ListaArgumentos   -> ',' Expresion ListaArgumentos | ε
```

La precedencia, de menor a mayor, es `||`, `&&`, `not`, comparación,
`+`/`-`, `*`/`/`, menos unario y primarios. Por tanto, `not a < b` significa
`not (a < b)`. Los operadores aritméticos binarios se agrupan por la izquierda.
Los sufijos solo se aplican a un primario iniciado por `ID`: `obj.f()[0]` es
sintácticamente válido, pero `new Clase().f()`, `(obj).f()` y `[1][0]` no lo son.
La validez de los tipos y de cada acceso se comprueba después en semántica.

Las comparaciones no se encadenan: `a < b < c` es inválido. Para combinar
condiciones se usa `&&` u `||`.

### Regla semántica de construcción

Cada clase declarada tiene un constructor implícito sin parámetros: `new Clase()`.
La expresión tiene el tipo de la clase construida, también cuando esta se declara
más adelante o tiene una clase base. No se declaran ni se heredan constructores
con parámetros; los métodos con nombre igual al de la clase siguen siendo métodos.
Aunque la BNF admite `new Clase(args)`, el análisis semántico rechaza cualquier
argumento y comprueba también las expresiones proporcionadas para informar sus
errores. Construir una clase no declarada es un error semántico.

## Reglas que pertenecen al parser, pero no a la BNF

1. El programa debe contener al menos una clase.
2. Debe existir al menos un `public main() { ... }` dentro de alguna clase.
3. Un `else` solo puede aparecer como parte de un `if`; la producción ya lo
   expresa y la recuperación de errores debe mantenerlo.
4. La recuperación en modo pánico se sincroniza con `;`, `}`, inicio de una
   sentencia o inicio de una clase, según el contexto.

## Fuera de alcance deliberadamente

No son válidos en esta versión: declaraciones o sentencias fuera del bloque
global, el ciclo `for`, `each` sin `repeat`, `repeat while`, el tipo primitivo
`decimal`, los operadores `and` y `or`, el
dos puntos después de `if`, ni separadores `.` en argumentos o parámetros.
Se podrán incorporar más adelante mediante una actualización explícita de esta
especificación y sus pruebas.

## Biblioteca de consola prevista (RC-1)

La API de entrada/salida se especificó en
[ESPECIFICACION_RUNTIME_CONSOLA.md](ESPECIFICACION_RUNTIME_CONSOLA.md).
Usará `import runtime.Consola;`, `Consola consola = new Consola();` y llamadas
como `consola.imprimirLinea(valor)` o `consola.leerInt()`. Todas esas formas
utilizan las producciones existentes de imports, tipos ID, construcción y
llamadas; los nombres no se convierten en palabras reservadas.

La biblioteca dispone de soporte semántico explícito y registro de símbolos
integrados; H2/H3 generan y ejecutan sus llamadas en las IR de referencia.
H4 aporta el runtime C nativo; H5 emitirá los cuerpos NASM del programa completo.
La BNF y sus 53 producciones permanecen vigentes.
