# IR del lenguaje completo: H3

Fecha: 10 de octubre de 2026. H3 completado en generación y ejecución de referencia.
El compilador sigue siendo Java 11+. Los intérpretes tipado y atómico ejecutan
el programa para comprobar el contrato antes del futuro backend NASM.
El runtime nativo corresponde a H4 y la emisión/enlace a H5.

Estado posterior: H4 ya implementa la biblioteca C y pasa 90/90 oráculos RC-1,
incluido SIGPIPE. Detalle en [RUNTIME_NATIVO_H4.md](RUNTIME_NATIVO_H4.md).
Las referencias a H4 pendiente en este documento describen el alcance de ese hito.

## Cobertura desde fuente

`GeneradorIr` consume todas las unidades válidas de `Main.analizarProyecto`.
Conserva símbolos, tipos, conversiones y firmas del AST semántico; no realiza
una nueva resolución de nombres. Cualquier unidad inválida bloquea el módulo.

| Construcciones | Comportamiento |
|---|---|
| Literales, locales, var/let, parámetros, return | Valores tipados y almacenamiento con identidad de símbolo. |
| Aritmética, comparaciones, negación, conversiones | int64 con wrap, binary64, errores de división y promociones explícitas. |
| if/else, while, repeat, cortocircuito | CFG tipado; solo etiquetas y saltos al linealizar. |
| Asignaciones simples y compuestas | Locales, campos y elementos; destino evaluado una vez y comprobado antes del RHS. La compuesta conserva la lectura anterior al RHS. |
| Métodos públicos/privados, sobrecargas, recursión | Firma estática resuelta; despacho público dinámico y privado directo. |
| new, campos, herencia e inicializadores | Reserva completa, valores predeterminados y llamadas explícitas al plan de inicialización. |
| Listas literales, vacías y anidadas | Longitud fija, elementos mutables, conversión contextual por elemento y aliasing por identidad. |
| repeat each y Exist | Colección evaluada una vez; cada lectura respeta las conversiones semánticas. |
| Cadenas e impresión | Igualdad por contenido, concatenación, Unicode/NUL, listas anidadas, objetos y null. |
| Imports y consola integrada | Composición en un módulo y seis operaciones RC-1 con origen y canales inyectables. |

Los nodos estructurales (clases, miembros, bloques, argumentos y tipos) organizan
la generación; los nodos de expresión producen valores o direcciones. El nodo
semántico `ELEMENTO_ITERADO` se materializa en la lectura del iterador. No existe
una barrera H3 para ramas no tomadas o métodos no invocados. Los nodos inesperados
se diagnostican con `NODO_IR_NO_SOPORTADO`, sin entregar un módulo parcial.

## Objetos e inicialización

`CREAR_OBJETO` reserva la instancia de su clase dinámica con todos los campos,
base primero, y sus valores predeterminados: 0, +0.0, false o null. Se asignan
antes de ejecutar cualquier expresión de inicialización, incluidos los campos
de la derivada que un método virtual podría leer durante la inicialización base.

Cada clase fuente tiene una función privada sintética `init_IR():void` registrada
en `ClaseIr.inicializador`. Su cuerpo llama directamente al inicializador de la
base y después evalúa los inicializadores propios en orden de declaración. Cada
`new` emite la reserva y una llamada explícita a esta función. El envoltorio de
entrada realiza la misma secuencia antes de main. El nombre interno contiene
`_`, que la gramática fuente no admite: no puede confundirse con un método del
usuario. Un método con el nombre de la clase continúa siendo un método ordinario.

La firma del inicializador se verifica en ambas IR. Solo esa metadata permite
una llamada directa privada desde otra clase; no amplía la privacidad fuente.
Las clases integradas y los fixtures manuales sin metadata no ejecutan ese plan.
Los campos homónimos de base y derivada conservan IDs y offsets distintos.

Los públicos despachan según la clase dinámica sin modificar la firma elegida
para una sobrecarga. El intérprete tipado busca la implementación en la jerarquía;
el atómico carga la vtable y el slot ya calculado y realiza `CALL_INDIRECT`.
Los privados llaman a su implementación declaradora. Una conversión a clase base
conserva la referencia y las sobrescrituras admiten retornos covariantes.

## Listas e iteración

Los elementos del literal se evalúan y convierten de izquierda a derecha antes
de reservar la lista. La invariancia de listas almacenadas se conserva: no se
convierte una lista mutable completa de int a float ni de derivada a base.
La asignación copia la referencia; modificar un alias afecta a los demás.
La igualdad de listas y objetos compara identidad, y la de cadenas contenido.

`repeat each` conserva la colección original y su longitud, recorre índices desde
cero y lee cada elemento al iniciar su turno. Cambiar un elemento futuro mediante
un alias resulta observable; reasignar la variable de colección o el iterador no
reemplaza la colección recorrida. Las promociones int→float y derivada→base se
aplican a la lectura del elemento. `Exist` ejecuta su bloque exactamente una vez
si la longitud es positiva. Ambas operaciones fallan si la colección es null.

Los literales sin un tipo de elemento almacenable, como `[]`, `[null]` o `[[], []]`,
pueden aparecer en contextos efímeros de longitud, identidad y conversión a texto.
El generador normaliza sus hojas vacías/null a ranuras nullable string. Solo
contienen cero elementos o referencias null; esa elección interna no altera esos
resultados ni relaja los tipos de variables, parámetros o listas mutables.

## Atomicidad y memoria de referencia

La proyección conserva las primitivas de [IR_ATOMICA.md](IR_ATOMICA.md): offsets
resueltos, cálculos intermedios explícitos, PARAM/CALL/GET_RETURN y saltos.
No añade árboles de expresión ni bucles al cuádruplo. Un objeto se reserva con
helper y después se inicializa con CALL; una lista se reserva con helper y sus
elementos se almacenan mediante LEA/STORE separados.

`MemoriaReferenciaIr` comparte celdas para mantener el aliasing. Los accesos
comprueban null antes del rango y generan errores estables con origen, igual
que las llamadas y divisiones. Las pilas de ejecución son explícitas: la recursión
de métodos e inicializadores no depende de la pila de llamadas Java.
El formateador de listas anidadas también utiliza una pila explícita; los objetos
se imprimen como `<ClaseDinamica>`, sin recorrer campos ni llamar métodos.

El harness admite un presupuesto opcional de bytes lógicos para reservas de
objetos/listas (cabecera 16 bytes y ranuras de 8). No cuenta strings, marcos ni
sobrecoste de la JVM, y no pretende reproducir el consumo nativo. La implementación
Java limita una lista a `Integer.MAX_VALUE - 8` elementos y mapea reservas fallidas
a `MEMORIA_AGOTADA`; el contrato nativo conserva índices y longitudes int64.
La consola permite inyectar una reserva fallida al leer una cadena. Estas pruebas
deterministas no equivalen a certificar recuperación de cualquier agotamiento JVM.

## Uso y pruebas

Desde la raíz con JDK 11+ (seleccionar JAVA_HOME si el java del sistema es Java 8):

```sh
python3 tools/compilar.py
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H3_objetos_listas.txt --ejecutar
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H3_objetos_listas.txt --ir /tmp/h3.ir --ir-tipada /tmp/h3.tipada.ir
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasH3
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
python3 tools/probar_consola.py
```

El ejemplo imprime `0`, `7`, `[1, 7, 3]`, `1.5`, `7.5`, `3.5` y `[<Hija>]`,
cada resultado en una línea. Demuestra inicialización base con despacho virtual,
aliasing y promoción del iterador sin cambiar el tipo de la lista.

Resultados verificados: **260 comprobaciones H3**, 206 de generación H2, 259
atómicas, 116 de IR tipada y 35 del contrato de ejecución; regresión previa de
frontend intacta, incluidos 110 casos de composición y 759 entradas malformadas.
H3 compara ambas IR con expectativas independientes de bytes, efectos, errores,
orígenes y salida parcial. También cubre imports, covarianza, campos homónimos,
metadatos de inicialización inválidos y agotamiento de memoria simulado.

**89/90 oráculos RC-1 pasan**: impresión de listas/objetos y reserva fallida se
incorporaron en H3. Solo queda el pipe roto real (SIGPIPE/EPIPE), para H4. Estas
cifras verifican ejecución de referencia; los helpers C, adaptadores, SIGPIPE,
layout restante de strings/constantes y pruebas nativas siguen pendientes.
EOF, errores y límites del harness mantienen las reglas documentadas en H2/RC-1.
