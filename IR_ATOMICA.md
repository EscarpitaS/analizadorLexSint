# IR atómica y layout previo a NASM

La proyección física admite perfiles Linux y Windows mediante DestinoNativoIr,
seleccionado antes de linealizar. Operaciones y operandos mantienen su estructura.
El ABI se registra en LayoutProgramaIr y el verificador valida el perfil.
Las vistas didácticas tipadas siguen siendo portables. Detalle en
[GENERACION_NATIVA_H5.md](GENERACION_NATIVA_H5.md).

Fecha inicial: 8 de octubre de 2026. Actualizado con H3: 10 de octubre de 2026. Formato `IR_ATOMICA_V1`, perfiles Linux/ELF64 y Windows/COFF.

La frontera del backend es **`ModuloAtomicoIr`**: código lineal, operandos hoja,
llamadas descompuestas, offsets y ubicaciones de argumentos resueltos. La IR
tipada anterior permanece como representación portable entre frontend y esta
fase. El backend no recibe AST, ámbitos semánticos ni búsquedas de campos por nombre.

```text
Proyecto válido → GeneradorIr → ModuloIr → VerificadorIr
                               ↓
                  LinealizadorIr + LayoutProgramaIr + ConvencionLlamadasIr
                               ↓
                  ModuloAtomicoIr → VerificadorAtomicoIr
                               ↓
                  InterpreteAtomicoIr / GeneradorEnsamblador NASM
```

## 1. Cuádruplos estrictamente atómicos

Cada instrucción contiene `(operación, fuente1, fuente2, destino)` y su origen.
Los operandos solo pueden ser valores hoja (`ValorIr`), etiquetas, destinos de
llamada resueltos o ubicaciones ABI. El modelo no admite expresiones ni listas
de suboperaciones como operandos. Un destino virtual contiene un temporal de
puntero definido por cargas anteriores, nunca un cálculo pendiente.

Para `a + b * c`, una vez cargados a/b/c:

```text
(MUL, %b, %c, %t0)
(ADD, %a, %t0, %t1)
```

No existe `ADD(a, MUL(b,c))`. Cada operación compleja del runtime se expresa
como una llamada explícita; sus detalles pertenecen al helper, no al backend.
Atomicidad significa una primitiva lógica por cuádruplo: una instrucción de
máquina puede necesitar cargar operandos o guardar el resultado en sus ranuras.
No se promete que todos los cuádruplos generen exactamente una instrucción x86.

Cargas y almacenes tampoco contienen árboles. Un campo resuelto a +16 se expresa:

```text
(LEA, %persona, 16, %direccion)
(LOAD, %direccion, 0, %valor)
```

Una lista usa temporales distintos para `indice * 8` y `bytes + 16`; después LEA.
Crear un literal de lista se baja a reserva mediante helper y una pareja LEA/STORE
por elemento, sin una instrucción de aridad variable. Las conversiones a texto,
concatenación, reservas, comprobaciones y división usan helpers identificados.
La división auxiliar debe preservar el cero fatal y MIN_INT/-1 con wrap de H0.

La igualdad de string usa `IGUAL_STRING`, con comparación por contenido y manejo
de null; `!=` añade NOT sobre el booleano devuelto. EQ/NE primitivas comparan
números, bool e identidad de objetos/listas. El verificador rechaza EQ/NE sobre
string para impedir comparaciones de direcciones accidentales. Las comparaciones
float conservan IEEE/NaN; no pueden implementarse comparando sus bits como int.

## 2. Control lineal

El código contiene LABEL, JUMP, JUMP_IF_FALSE, SET_RETURN y RETURN. No conserva
if/else, while, repeat ni árboles de control. Un condicional de dos destinos se
convierte en dos saltos independientes:

```text
(JUMP_IF_FALSE, %condicion, L_else, _)
(JUMP, L_then, _, _)
```

Las etiquetas incluyen el nombre físico de la función para evitar colisiones
entre métodos. JUMP_IF_FALSE admite bool o int con prueba de cero; el frontend
sigue exigiendo bool en las condiciones del lenguaje. El cortocircuito ya viene
expresado como rutas del CFG y se conserva al linealizar.

## 3. Layout ya resuelto

`LayoutProgramaIr` calcula antes del backend:

| Elemento | Representación versión 1 |
|---|---|
| int, float, bool, referencias y direcciones | Ranuras de 8 bytes, alineadas a 8. Bool normalizado 0/1. |
| Cabecera de objeto | 16 bytes: puntero a vtable en +0, descriptor de clase en +8. |
| Campos | Base primero; primer campo +16, siguientes +24, +32… |
| Cabecera de lista | 16 bytes: longitud int64 en +0, descriptor de elemento en +8. |
| Elementos de lista | Datos desde +16, paso de 8 bytes. |
| Receptor, parámetros, locales y todos los temporales | Ranuras distintas con desplazamientos negativos de RBP. |
| Marco | Múltiplo de 16, incluye ranuras y área fija de argumentos salientes. |

Los campos se identifican por ID de símbolo. Un campo privado de la base y uno
homónimo de la derivada mantienen offsets diferentes. La clase derivada conserva
el prefijo de la base; el orden de archivos no cambia la asignación heredada.

Cada valor, incluidos los temporales auxiliares de linealización, tiene su offset
antes de emitir NASM. Locales y valores usan tablas separadas, evitando colisiones
accidentales entre IDs de ambos espacios. El exportador muestra cada ranura como
`%t0:tipo[rbp-24]` y publica tamaños de marco/área saliente. Las constantes siguen
siendo hojas; los float se exportan por bits binary64 para evitar ambigüedad.

El área saliente se reserva conservadoramente con `align16(max_argumentos * 8)`.
Ocupa la parte inferior del marco, desde RSP, sin solaparse con las ranuras.
Los argumentos que van por registro no necesitan esas posiciones; reservar el
máximo lógico mantiene el diseño sencillo y permite reducirlo posteriormente.
No se cambia RSP al preparar cada llamada.

## 4. Llamadas y RC-1

Antes de PARAM se evalúan receptor y argumentos, en el orden de H0, y se guardan
los resultados en temporales. Cada grupo PARAM es contiguo hasta su llamada;
ningún cálculo, salto o llamada anidada puede interrumpirlo.

```text
(PARAM, %receptor, rdi, _)
(PARAM, %arg1, rsi, _)
(CALL, fn_resuelta, 2, _)
(GET_RETURN, _, _, %resultado)
```

El receptor cuenta como argumento. Para impresión:

```text
(PARAM, %consola, rdi, _)
(PARAM, %texto, rsi, _)
(LLAMAR_RUNTIME, CONSOLA_IMPRIMIR_LINEA, 2, _)
```

Una llamada void no tiene GET_RETURN. Las demás tienen exactamente uno inmediato,
con el tipo declarado. SET_RETURN prepara el valor de salida de un método y
RETURN constituye el control de retorno. No se fusionan cálculos con llamadas.

`ConvencionLlamadasIr` asigna **System V AMD64**, compatible con H0:

- int/bool/punteros: RDI, RSI, RDX, RCX, R8, R9; el receptor consume la primera
  posición de esta clase.
- float: XMM0…XMM7, contados independientemente de los registros enteros.
- Argumentos que exceden sus registros: posiciones explícitas `[rsp+0]`, `[rsp+8]`…
  en el llamador, `[rbp+16]`, `[rbp+24]`… en el llamado después de guardar RBP.
- Retorno int/bool/puntero en RAX; float en XMM0. RSP debe estar alineado a 16
  antes de CALL. READ_PARAM copia las ubicaciones entrantes a las ranuras locales.

Referencia: [interfaz Unix de 64 bits de NASM](https://www.nasm.us/doc/nasm13.html#section-13.3).
El backend utiliza las ubicaciones ya anotadas; no vuelve a clasificar argumentos.
Las llamadas no son variádicas. Todos los valores vivos permanecen en sus ranuras;
el emisor debe preservar RBP y cualquier otro registro no volátil que decida usar.

Los slots virtuales también se calculan antes del backend: métodos públicos,
slots heredados estables, override reemplazando el destino y privados excluidos.
La llamada virtual emite por separado LOAD de la vtable (+0), LOAD de su slot
(+8*n), PARAM y CALL_INDIRECT. No consulta jerarquías durante la emisión.
El descriptor de CALL_INDIRECT reserva **R11** para su destino; el texto exportado
muestra `*r11<-%puntero[rbp-offset]`. Después de preparar todos los PARAM, el
emisor debe recargar la ranura del puntero en R11 y ejecutar `call r11`, sin
llamadas intermedias. Así no pisa RDI…R9 ni XMM0…XMM7. R11 es volátil: no se
conserva el puntero allí entre cuádruplos o a través de otra llamada.

**LLAMAR_RUNTIME invoca adaptadores del lenguaje**, no directamente los helpers C
RC-1. H4 implementa dichos adaptadores y sus puentes en `RUNTIME_NATIVO_H4.md`. Estos últimos retornan un estado en EAX y entregan datos por punteros out;
GET_RETURN espera el valor del lenguaje en RAX/XMM0. El adaptador nativo
comprueba estado/EOF/errores y produce el retorno de lenguaje. La metadata de
origen se prepara en R10 al llamar un adaptador; este debe preservarla antes de
cualquier llamada C para elaborar el diagnóstico. No modifica la aridad pública.
Los nombres `rt_lang_*` del cuádruplo se definen en el soporte NASM exportado
por módulo. Sus puentes llaman a símbolos C existentes de la biblioteca H4.
El soporte no incluye todavía los cuerpos de métodos ni la entrada nativa H5.

### Guardas obligatorias del emisor nativo

El prólogo estándar es `push rbp; mov rbp, rsp; sub rsp, marco.tamano`.
CALL coloca la dirección de retorno de 8 bytes: al entrar, RSP módulo 16 es 8;
guardar RBP lo lleva a 0. Por ello **marco.tamano incluye ranuras y área saliente
y es múltiplo de 16**. El área saliente se reserva una vez; preparar PARAM no
agrega pushes que cambien RSP. Si el backend guarda otros registros no volátiles,
debe incorporar esos bytes y recalcular la alineación antes de cada CALL.
El epílogo estándar es `mov rsp, rbp; pop rbp; ret`.

DIVIDIR_INT recibe dividendo en RDI y divisor en RSI y retorna int64 en RAX.
Sus dos guardas preceden a `cqo`/`idiv`: divisor cero genera el error fatal
DIVISION_POR_CERO con origen; MIN_INT/-1 devuelve MIN_INT por wrap. El helper de
referencia aplica ambas explícitamente. El esquema NASM pendiente es:

```nasm
    test rsi, rsi
    jz .division_por_cero
    mov rax, rdi
    cmp rsi, -1
    jne .normal
    mov rcx, 0x8000000000000000
    cmp rax, rcx
    je .retornar                ; MIN_INT / -1: wrap sin idiv
.normal:
    cqo
    idiv rsi
.retornar:
    ret
```

Este fragmento requiere una rama `.division_por_cero` que transfiera al adaptador
fatal de H4, conservando R10 (origen), emita el diagnóstico y termine con 1;
esa rama nunca debe alcanzar idiv ni devolver un resultado. MIN_INT se carga
mediante `mov reg64, imm64`, porque `cmp reg64, imm` no codifica ese inmediato
de 64 bits. En NASM la instrucción se escribe `idiv rsi`, sin sufijo `q`.
Estas son restricciones del backend por implementar, no ensamblador generado.

## 5. Verificación y alcance

`VerificadorAtomicoIr` rechaza instrucciones/tipos incompatibles, expresiones
imposibles por el modelo, offsets inválidos o solapados, etiquetas duplicadas,
saltos inexistentes, caída fuera de función, código inalcanzable y temporales
sin definición dominante. También valida la secuencia PARAM/CALL/GET_RETURN,
sus tipos, ubicaciones ABI, aridad y procedencia de punteros virtuales.
La emisión y el intérprete vuelven a validar esta frontera antes de consumirla.

La auditoría de consistencia también valida la implementación más derivada y la
cobertura completa de cada vtable, la identidad del receptor del que se cargó
el destino indirecto, los tipos de clase anidados y las categorías de valores.
La procedencia se propaga por COPY hasta punto fijo y no depende del orden
textual de bloques dominantes. Tablas virtuales y direcciones de función no se
tratan como celdas mutables. Las etiquetas físicas no pueden colisionar entre
funciones ni con símbolos de funciones; los tamaños de clase corresponden a sus
ranuras. [Resultados y alcance de la auditoría](AUDITORIA_IR.md).

`InterpreteAtomicoIr` ejecuta cuádruplos H2/H3 usando las ranuras físicas calculadas,
con pila explícita y canales inyectables. Las pruebas comparan bytes exactos,
fallos/orígenes y salida parcial con el intérprete de IR tipada y con expectativas
independientes. Incluyen recursión, argumentos, cortocircuito y métodos públicos.
También comprueban prólogo/epílogo y áreas salientes con cantidades pares e
impares de ranuras, separación de R11 respecto de todos los argumentos, y los
casos extremos de división. Esas pruebas validan layout, ABI anotada y ejecución
de referencia; aún no ejecutan instrucciones nativas.

La **representación atómica** cubre H3 desde fuente: objetos, herencia, campos,
listas e iteraciones se ejecutan en ambos intérpretes. `PruebasH3` comprueba
aliasing, campos homónimos, despacho dinámico, conversiones y orden de efectos.
Cada layout de clase conserva una firma opcional de inicializador privado:
la reserva asigna todos los valores predeterminados, y después un CALL explícito
ejecuta `init_IR()` (base primero). El envoltorio de entrada hace lo mismo antes
de main. Esta metadata también se valida; no contiene AST ni árboles de cálculo.
Detalle en [IR_EJECUTABLE_H3.md](IR_EJECUTABLE_H3.md).
H4 completa RtString/constantes, gestores, adaptadores y helpers C; detalles y
pruebas de enlace en `RUNTIME_NATIVO_H4.md`. El backend del programa completo
NASM/enlace sigue pendiente de H5.
No se presenta un ejecutable nativo todavía.

## Uso y pruebas

Desde la raíz, con JDK 11+:

```sh
python3 tools/compilar.py
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H2_control.txt --ir /tmp/control.ir
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H2_control.txt --ejecutar
# Opcional: conservar también la IR portable por bloques para inspección.
java -cp build/AnalizadorLexSint.jar CompilarIr untitled/ejemplos/ir/H2_control.txt --ir /tmp/control.ir --ir-tipada /tmp/control.tipada.ir
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasIrAtomica
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
```

`--ir` (alias `--ir-atomica`) y stdout sin opción exportan cuádruplos.
`--ejecutar` utiliza ahora el intérprete atómico. `--ir-tipada` conserva la vista
portable. Ninguna salida puede sobrescribir una unidad fuente, y las dos
representaciones no pueden exportarse al mismo archivo. El presupuesto opcional
`--max-pasos` cuenta cuádruplos al ejecutar; sigue siendo un límite del harness.
EOF, errores y bytes del programa conservan RC-1.

## Perfil Windows en H5

La distribución System V descrita aquí permanece como perfil por defecto.
Windows asigna por posición, con receptor incluido, cuatro registros iniciales
y excedentes en [rsp+32]/[rbp+48]. Reserva shadow space conservador de 32 bytes
aun en hojas. Los offsets de campos, identidad, tipos y operaciones se conservan.
Cambiar el destino exige regenerar la proyección física; mezclar su ABI se rechaza.
