# Visualización didáctica de código intermedio

Fecha: 10 de octubre de 2026. Implementación de consulta en `interfaz/`.

En **Resultados → IR del proyecto**, la vista inicial es **Cuádruplos didácticos**.
También se ofrecen **Triplos (tercias)**, **Tres direcciones**, **Atómica simplificada**
y **Detalles técnicos**. Todas admiten copiar/guardar; las tablas se exportan
como TSV UTF-8 y las vistas de texto como `.ir`. Errores del proyecto y edición
posterior desactivan las exportaciones hasta analizar nuevamente.

## Representaciones y fuente de los datos

`PresentacionDidacticaIr.desdeTipada` consume el `ModuloIr` ya validado. No vuelve
a analizar el texto fuente ni modifica instrucciones, símbolos, CFG o temporales.
Crea filas de presentación, numeradas desde cero en todo el proyecto, con función,
operador, dos argumentos, resultado y ubicación `:línea:columna`.

- Los operandos son variables, constantes, temporales `tN`, direcciones `dN`,
  etiquetas `LN` y argumentos lógicos `argumento_N`. El receptor se llama `este`.
- Las variables conservan su nombre; las homónimas o nombres que coincidirían con
  un alias reciben `#id` para mantener su identidad. Los campos incluyen clase
  declaradora, y las firmas identifican sobrecargas.
  Esto incluye variables como `L0`, para distinguirlas de las etiquetas.
- Strings usan escapes solo en la presentación, conservando su contenido real.
  Los float se imprimen con el formateador Java 11 fijado, incluidos ceros con signo.
- Las etiquetas y aliases se reinician por función, identificada en una columna.
  La numeración de filas sigue siendo global para que `(n)` sea inequívoco.

Una dirección de local se omite únicamente cuando todos sus usos son lecturas
/escrituras directas de esa misma ranura. CARGAR se muestra como `ASIGNAR variable
→ temporal`; GUARDAR como `ASIGNAR valor → variable`. **Las lecturas conservan su
temporal**: en `a + cambiarA()`, el primer valor de a debe sobrevivir a la llamada.
No se hace sustitución textual de temporales por variables ni se reordenan efectos.
Las direcciones de campos y elementos se conservan como operaciones explícitas;
las comprobaciones implícitas de esas operaciones mantienen su semántica IR.

Las llamadas se muestran como PARAMETRO por receptor/argumento, llamada lógica
y OBTENER_RETORNO cuando procede. El nombre de destino conserva despacho directo,
virtual o runtime. Crear una lista se explica mediante CREAR_LISTA(longitud),
OBTENER_DIRECCION_ELEMENTO y ESCRIBIR_MEMORIA por elemento; sus valores ya fueron
evaluados por la IR tipada. Los saltos condicionales se presentan como
SALTAR_SI_FALSO y SALTAR, con etiquetas explícitas; no se reconstruyen while/if.
Estas expansiones pedagógicas explican la operación original y no constituyen
un tercer intérprete ni una nueva entrada para el backend.

## Cuádruplos, tercias y tres direcciones

Un cuádruplo contiene `(operador, argumento1, argumento2, resultado)`.
La tabla muestra los cuatro componentes por separado. Seleccionar una fila
muestra debajo su equivalente de tres direcciones. La vista de texto conserva
los bloques separados, etiquetas a la izquierda e instrucciones indentadas.

Las tercias contienen `(operador, argumento1, argumento2)`. Una operación que
produce un valor tiene resultado implícito identificado por su número de fila;
un uso posterior aparece como `(n)`. Cada referencia usa la identidad del
valor de la IR y la función, no una sustitución de cadenas. Por eso una constante
como `"t0"` conserva su texto y jamás se transforma en referencia a una fila.

En escrituras, el destino ocupa el segundo argumento: `(ASIGNAR, valor, variable)`
o `(ESCRIBIR_MEMORIA, valor, dirección)`. Las etiquetas también conservan su
nombre como argumento. Los saltos apuntan a etiquetas y las referencias a
resultados no reemplazan esos destinos de control. El orden visible no implica
que todas las filas se ejecuten: los saltos siguen determinando el recorrido.

## IR física y diccionario de operaciones

**Atómica simplificada** recorre exactamente los mismos cuádruplos de
`ModuloAtomicoIr`, con nombres españoles y aliases para operandos. Por ejemplo:

| Interna | Presentación |
|---|---|
| LEA_LOCAL | OBTENER_DIRECCION_VARIABLE |
| LEA | CALCULAR_DIRECCION |
| LOAD / STORE | LEER_MEMORIA / ESCRIBIR_MEMORIA |
| COPY | ASIGNAR |
| ADD / MUL | SUMAR / MULTIPLICAR |
| LABEL / JUMP | ETIQUETA / SALTAR |
| READ_PARAM / GET_RETURN | RECIBIR_PARAMETRO / OBTENER_RETORNO |

Los argumentos ABI se presentan por posición lógica, sin RDI/RBP/XMM ni bits64.
Las referencias a locales recuperan nombres del módulo tipado y su tabla de
layout ya calculada. Se mantienen offsets numéricos de accesos físicos donde
son parte de la operación; la vista está identificada como física simplificada,
no se hace pasar por la proyección portable.

**Detalles técnicos** conserva la IR física, la IR tipada original y la tabla
de cuádruplos físicos con su impresión/exportación anterior. La CLI CompilarIr,
los verificadores, los intérpretes y el backend siguen consumiendo las
representaciones originales. No se cambió ningún archivo de modelo IR, runtime
ni backend para implementar estas vistas.

La portabilidad contratada en [CONTRATO_MULTIPLATAFORMA.md](CONTRATO_MULTIPLATAFORMA.md)
se aplica a la IR tipada; las vistas didácticas no dependen del destino. La IR
física de la GUI y sus detalles técnicos siguen seleccionando Linux por defecto;
H5 permite elegir Windows mediante la CLI, antes de la proyección física. La GUI todavía no ofrece una flag de destino ejecutable.
