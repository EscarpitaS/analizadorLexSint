# IR tipada (H1)

Modelo inmutable de tres direcciones, en `untitled/src/intermedio/`, compatible
con Java 11 y el paquete por defecto actual. El generador y los intérpretes actuales se documentan en `IR_EJECUTABLE_H3.md`.
`PruebasIr` construye módulos **manualmente** para comprobar su contrato.

La frontera del backend ahora es `ModuloAtomicoIr`: cuádruplos lineales, llamadas
descompuestas y offsets calculados, descritos en [IR_ATOMICA.md](IR_ATOMICA.md).
`--ir` exporta ese formato y `--ir-tipada` conserva el formato de este documento.

## Estructura

- `ModuloIr`: nombre, clases, funciones y firma de entrada opcional. Entrada
  ausente significa biblioteca, no un ejecutable al que se le adivina main.
- `ClaseIr`: nombre, base, símbolos de campos declarados, marca de consola
  integrada y firma opcional del inicializador privado `init_IR():void`. Su
  implementación es una función IR normal. El layout se calcula al linealizar.
- `FirmaIr`: clase declaradora, nombre, parámetros, retorno y visibilidad. El ID
  textual `Clase::metodo(int,list<float>)` excluye el retorno, que no distingue
  sobrecargas. Se comprueban overrides públicos y covarianza de retorno de clase.
- `FuncionIr`: firma, receptor explícito en IR, parámetros, locales y bloques.
  El primer bloque es la entrada; no depende de llamarse `inicio`.
- `BloqueIr`: instrucciones secuenciales y un terminador obligatorio al final:
  salto, salto condicional bool o retorno. Las instrucciones no contienen saltos.
- `ValorIr`: constante, parámetro o temporal tipado. No es una variable mutable.
- `SimboloIr`: identidad de almacenamiento local/campo, nombre visible, clase
  declaradora, tipo y ubicación. Los IDs de símbolos son únicos en el módulo.
- `TipoIr`: escalares, objetos, listas anidadas, null, void y `dir<T>`. Direcciones
  son valores internos, sin literal, parámetro, campo, retorno ni elemento de lista.
- `OrigenIr`: archivo/línea/columna originales o `GENERADO` explícito. Cada
  instrucción y terminador conserva origen.

Los temporales tienen una única definición por función y los parámetros están
definidos a su entrada. Esto **no es una conversión del programa a SSA**: locales
mutables y resultados de uniones de control usan ranuras con carga/almacenamiento;
no hay instrucciones phi. Una instrucción dentro de un ciclo puede ejecutarse
repetidamente conservando su identificador estático. IDs de valores/bloques pueden
repetirse en otras funciones. El modelo admite `_` en nombres internos generados;
eso no amplía los identificadores de la gramática fuente.

## Instrucciones y tipos

Las aridades y tipos son exactos; las promociones no se sobreentienden:

| Operación | Operand(os) → resultado |
|---|---|
| `COPIAR` | T → T |
| `DIRECCION_LOCAL` | símbolo local declarado → dir<T> |
| `DIRECCION_CAMPO` | receptor de la clase declaradora o derivada + símbolo de campo → dir<T> |
| `DIRECCION_ELEMENTO` | list<T>, int → dir<T> |
| `CARGAR` | dir<T> → T |
| `GUARDAR` | dir<T>, T → sin resultado |
| `SUMAR`, `RESTAR`, `MULTIPLICAR`, `DIVIDIR` | dos int o dos float → mismo tipo |
| `NEGAR` | int/float → mismo tipo |
| `NO` | bool → bool |
| `MENOR`, `MENOR_IGUAL`, `MAYOR`, `MAYOR_IGUAL` | dos números de igual tipo → bool |
| `IGUAL`, `DISTINTO` | dos valores de igual tipo almacenable, null/null o referencia/null → bool |
| `CONVERTIR` | int→float; valor→string; null→referencia; objeto→su base, con etiqueta explícita |
| `LLAMAR_RUNTIME` | receptor Consola integrado, string para impresión → void; lecturas → int/float/bool/string |
| `LLAMAR_DIRECTA`, `LLAMAR_VIRTUAL` | receptor y argumentos de firma declarada → retorno, o sin resultado si void |
| `CONCATENAR` | string, string → string |
| `CREAR_LISTA` | cero o más elementos T → list<T> |
| `LONGITUD_LISTA` | list<T> → int |
| `CREAR_OBJETO` | cero operandos → objeto de clase declarada, con todos sus campos predeterminados |
| `COMPROBAR_NULO` | referencia → sin resultado |
| `COMPROBAR_INDICE` | list<T>, int → sin resultado |

Una comparación numérica mixta requiere promoción previa. Una comparación
entre objeto derivado y base requiere conversión al tipo común. No existe una
conversión de `list<int>` mutable a `list<float>` mutable. Los literales de lista
contextuales convierten cada elemento antes de crear la lista.

Las operaciones abstractas respetan errores y orden de H0. El verificador
comprueba tipos, no intenta probar que un índice o divisor sea válido en tiempo
de ejecución. `CREAR_OBJETO` reserva una instancia de la clase dinámica y coloca
los valores predeterminados de todos sus campos, incluidos los heredados. Cada
`new` fuente emite después `LLAMAR_DIRECTA` a su inicializador de metadata; este
llama al de la base y evalúa los inicializadores propios en orden de declaración.
El envoltorio de entrada ejecuta ese mismo plan antes de main. La operación de
reserva aislada no ejecuta inicializadores expresados por el usuario. Las comprobaciones de nulo/rango forman parte de las operaciones
de acceso aunque no se escriba un `COMPROBAR_*` explícito.

## Verificación y exportación

`new VerificadorIr().verificar(modulo)` devuelve un `ResultadoVerificacionIr`
con diagnósticos inmutables: código, mensaje, función, bloque, índice de
instrucción (el tamaño del bloque identifica al terminador) y origen. Rechaza:

- Clases/bases inexistentes, ciclos de herencia, declaraciones duplicadas y tipos
  de objeto desconocidos, incluso dentro de listas/direcciones.
- Símbolos no declarados o suplantados por otro objeto con igual nombre/ID;
  clase y rol incorrectos en campos/locales.
- Receptor/parámetros incompatibles con firma, métodos duplicados, overrides
  incompatibles, llamada sin implementación, argumentos/resultado incorrectos,
  llamada virtual a privado o directa a público, acceso directo privado desde
  otra clase, salvo la llamada al inicializador privado validado en la metadata
  de su clase. Una firma de inicializador falsa también se rechaza. Los símbolos de campos ya vienen resueltos; la privacidad fuente
  es responsabilidad del semántico y no se vuelve a analizar aquí.
- Instrucciones con aridad, metadatos o tipos incorrectos, resultados ausentes
  o indebidos y retorno incompatible.
- Bloques duplicados, vacíos de terminador, saltos a destinos inexistentes y
  **bloques inalcanzables**. El generador debe omitir código fuente inalcanzable.
- Valores indefinidos, IDs redefinidos o usados con otro tipo/categoría y
  temporales cuya definición no domina el uso. Dentro de un bloque, la definición
  debe preceder al uso. Entre bloques se calculan dominadores hasta punto fijo;
  no basta el orden de la lista. Esto cubre ramas y vueltas que se ejecutan cero veces.
- Firma de entrada no declarada o que no corresponda a public main():void.

El verificador no sustituye el análisis de inicialización de locales del frontend,
ni la comprobación posterior de layout, vida de direcciones o ejecución del runtime.
Las comprobaciones de estructura/tipos son el alcance de H1, no una demostración
completa del comportamiento de programas arbitrarios.

Los constructores rechazan inmediatamente nombres mal formados, tipos imposibles
y componentes obligatorios nulos. Un bloque sin terminador sí se puede construir
para diagnosticar una IR incompleta. Una IR representable pero inconsistente
produce diagnósticos del verificador, sin ejecutar sus instrucciones.

`new ImpresorIr().imprimir(modulo)` verifica antes de exportar y rechaza IR inválida.
El texto conserva orden de declaraciones, bloques e instrucciones; usa LF, tipos
explícitos, IDs estables y escapes de cadenas/archivos. No introduce timestamps
ni identidades de objetos Java. Módulos equivalentes construidos en el mismo
orden producen texto idéntico. El texto es un formato de inspección, sin parser
de deserialización ni ensamblador en H1.
El formateo de constantes float utiliza la implementación Java 11 fijada del
proyecto, igual que el runtime y la vista didáctica, independientemente del JDK
que aloje al compilador. La vista física conserva los bits binary64 originales.

## Puente hacia el AST validado

`TipoIr.desdeSemantico` convierte tipos completos y rechaza ERROR/VACIO.
`CatalogoSimbolosIr` conserva la identidad del `Semantico.Simbolo` resuelto mediante
un mapa de identidad, asignando s0, s1… al registrar declaraciones en orden estable.
Dos locales homónimas de ámbitos diferentes tienen IDs distintos. No debe asignar
IDs por orden accidental de primer uso ni reutilizar un catálogo entre módulos.

`EntradaProyectoIr.desdeResultados` recibe **todos** los resultados finales de
`Main.analizarProyecto`: comprueba éxito global, AST validado, ámbito global,
unidades duplicadas y presencia de cada dependencia registrada. Rechaza un
proyecto incompleto antes de generar. Los AST siguen perteneciendo
al frontend; `GeneradorIr` consume la instantánea válida y recorre hijos semánticos
para preservar conversiones. La UI bloqueará resultados desactualizados en H6.

`SeleccionEntradaIr.elegir(firmas, claseOpcional)` elige main único o la clase
indicada y diagnostica ausencia/ambigüedad. Un módulo biblioteca puede verificar
sin main; crear un ejecutable requerirá esta selección y sus inicializadores.

## Pruebas

Con JDK 11+ y el JAR construido:

```sh
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasIr
java -cp build/AnalizadorLexSint.jar PruebasContratoEjecucion
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
# Opcional: exportar el módulo construido manualmente por el fixture:
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasIr /tmp/ejemplo.ir
```

Las suites forman parte de `PruebasRegresion`. Cubren el catálogo de instrucciones,
tipos, memoria abstracta, llamadas, selección de entrada, módulos inmutables,
exportación/escapes, flujo/dominancia en ramas/ciclos, puente con el frontend y
rechazos antes de exportar. La suite de H0 comprueba los límites int64, wrap,
división extrema y por cero, literales float, underflow y valores IEEE.
`PruebasGeneracionIr` añade ejecución de control y consola en H2. `PruebasH3`
cubre objetos/listas, aliasing, inicialización, despacho y errores en ambas IR.
El backend nativo sigue pendiente de H5.

## Extensión de consola implementada en H2

RC-1 está en [ESPECIFICACION_RUNTIME_CONSOLA.md](ESPECIFICACION_RUNTIME_CONSOLA.md).
`RuntimeConsolaId` es un catálogo cerrado. `LLAMAR_RUNTIME` exige un receptor
Consola de una clase marcada como integrada, segundo operando string en impresión,
resultado del tipo exacto en lectura y origen. No permite simular intrínsecos
con una clase del usuario que tenga el mismo nombre. La impresión lleva
A_STRING explícita después de evaluar argumentos y comprobar el receptor.
`ClaseIr.consolaIntegrada` distingue esa identidad y el verificador rechaza
campos/herencia en la clase integrada. El impresor conserva ID y marca runtime.

La implementación actual y sus límites se detallan en
[IR_EJECUTABLE_H3.md](IR_EJECUTABLE_H3.md).
