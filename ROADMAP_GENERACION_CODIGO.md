# Roadmap: código intermedio, ensamblador y ejecutable

Fecha inicial: 7 de octubre de 2026. Estado actualizado: 11 de octubre de 2026.

## Estado actual

- **H0 completado:** destino NASM/Linux x86-64/ELF64 y contrato versión 1 en
  `CONTRATO_EJECUCION.md`. Se definieron números, evaluación, referencias,
  listas, cadenas, inicialización, entrada y memoria. Oráculos escalares probados.
- **H1 completado:** modelo inmutable en `intermedio/`, verificador de tipos y
  dominancia, impresor determinista y puente al frontend, documentados en
  `IR_TIPADA.md`. Las suites `PruebasIr` y `PruebasContratoEjecucion` se integran
  en regresión. Los fixtures IR se construyen manualmente.
- **H2 implementado (8 de octubre):** AST→IR verificada e intérprete escalar,
  control, llamadas, recursión y consola integrada. CLI `CompilarIr`, ejemplos
  ejecutables y suite integrada. Alcance en `IR_EJECUTABLE_H2.md`.
- **H3 completado (10 de octubre):** objetos, campos, herencia, inicializadores,
  despacho virtual, listas y sus iteraciones desde fuente hasta ejecución en
  ambas IR. Suite de 260 comprobaciones integrada; detalle en `IR_EJECUTABLE_H3.md`.
- **RC-1 implementado en referencia y C:** 89/90 oráculos Java y 90/90 nativos,
  incluidos SIGPIPE/EPIPE real, listas/objetos y fallos simulados.
- **IR atómica implementada:** cuádruplos, saltos, PARAM/CALL/GET_RETURN,
  verificador e intérprete diferencial. Layout de campos, frames, slots virtuales
  y argumentos System V calculado antes del backend.
  Detalle en `IR_ATOMICA.md`. El backend completo se implementa en H5.
- **H4 completado (10 de octubre):** layout de strings/constantes/descriptores,
  runtime C11, gestor de memoria y puentes ABI por módulo. Pruebas de helpers,
  formato Java11 y enlace nativo; detalle en `RUNTIME_NATIVO_H4.md`.
- **Contrato ampliado:** perfiles Linux x64 y Windows x64 en
  `CONTRATO_MULTIPLATAFORMA.md`. H5 implementa proyección ABI, runtime y
  `--target`; las pruebas nativas Windows siguen pendientes.
- **H6 implementado (11 de octubre):** vistas didácticas originales conservadas y
  pestaña ASM/ejecutable con destino, entrada, IR física por perfil, exportación,
  ensamblado, enlace y consola. Trabajadores cancelables, herramientas configurables,
  detección de dependencias modificadas y publicación protegida. JAR autónomo con
  runtime; regresión y construcción/ejecución Linux aprobadas, enlace Windows UCRT
  comprobado. Aceptación manual GUI/Windows pendiente. Detalle en
  `INTEGRACION_NATIVA_H6.md`.
- **H5 implementado:** núcleo NASM compartido y adaptadores Linux/Windows,
  entrada, cuerpos completos y builder por etapas. Linux nativo y Windows bajo
  Wine comparados con oráculos; aceptación nativa Windows pendiente. Detalle
  en `GENERACION_NATIVA_H5.md`. H6 integrado. H7 implementado con optimización explícita y conservadora.

- **H7 implementado (11 de octubre):** propagación/plegado, cálculos muertos,
  simplificación de flujo y asignación local R8/R9. GUI/CLI con opción explícita,
  IR original/optimizada y resumen didáctico; no requiere SSA. Regresión y corpus
  nativo de 37 programas; aceptación Windows real pendiente. Detalle en
  `OPTIMIZACION_H7.md`.

El programa generado final será un ejecutable nativo desde consola sin JVM.
Java sigue siendo la implementación del compilador y del intérprete de pruebas.
Las bibliotecas H4 se compilan con GCC/ar; los puentes se verifican también
con NASM local, sin modificar paquetes del sistema.


## Objetivo y punto de partida

Transformar los proyectos válidos del lenguaje canónico en código intermedio
(IR), ensamblador y ejecutables, preservando su comportamiento.

La referencia sigue siendo `GRAMATICA_CANONICA.md` y `CONTRATO_SEMANTICO.md`.
El contrato de salida ya proporciona tipos, identidad de símbolos, ámbitos,
conversiones, clase construida, receptor y despacho de llamadas.

La entrada del generador debe ser el resultado final de proyecto elaborado en
`aplicacion/Main.java`. Solo se genera si todas las unidades alcanzables son
válidas y entregan `getAstValidado()`. No basta el éxito aislado de `Semantico`
si hay errores léxicos, sintácticos o de imports en otra unidad.

Se recorre `getHijosSemanticos()` para preservar conversiones. Para generar un
destino de asignación se conserva además su identidad sintáctica: una promoción
de lectura no convierte ese destino en un lugar de almacenamiento distinto.

Flujo propuesto:

```text
Proyecto validado → módulo IR tipado → verificador IR → intérprete IR
                                            ↓
                                  backend de ensamblador
                                            ↓
                                ensamblador + enlazador
                                            ↓
                                        ejecutable
```

El intérprete constituye una implementación de referencia para las pruebas.
Sus resultados también deben compararse con expectativas independientes del
contrato; la coincidencia entre intérprete y backend por sí sola no demuestra
que ambos sean correctos.

## H0. Definir el contrato de ejecución

Antes de emitir instrucciones, documentar las decisiones pendientes en
`CONTRATO_EJECUCION.md`:

- Plataforma, arquitectura, formato de ensamblador y herramientas de ensamblado
  y enlace. El destino concreto es NASM/Linux x86-64/ELF64, acordado en H0.
- Representación de `int`, `float` y `bool`; rango de literales, overflow,
  redondeo de división entera y errores de división por cero.
- Orden de evaluación de operandos, argumentos, receptor e índices; comportamiento
  de cortocircuito de `&&` y `||` y número de evaluaciones de cada expresión.
- Representación, codificación, igualdad y concatenación de cadenas; conversiones
  a cadena de números, booleanos, `null`, listas y objetos que el semántico admite.
- Representación e igualdad de referencias, listas y objetos. Comportamiento
  de acceso mediante `null`, índices inválidos y agotamiento de memoria.
- Inicialización predeterminada de campos y orden de ejecución de inicializadores
  de clases base y derivadas. Los constructores siguen siendo implícitos sin argumentos.
- Semántica operacional exacta de `repeat (condición)`, `repeat each` y `Exist`.
  El análisis conservador de flujo no determina por sí solo cómo se ejecutan.
- Entrada del programa: la gramática admite varios `public main()`. Seleccionar
  explícitamente clase/método de entrada y definir la creación de su receptor;
  no asumir que el método es estático ni ampliar la gramática.
- Modelo de memoria y liberación de recursos para la primera versión.

**Entregable:** contrato con ejemplos de comportamiento esperado y decisiones
cerradas. Las decisiones deben completar el lenguaje vigente, sin ampliar sus
construcciones silenciosamente.

## H1. Diseñar la representación intermedia

Implementar IR de tres direcciones, tipado y organizado por módulos, funciones
y bloques básicos. Comenzar sin SSA ni optimizaciones.

- Valores: constantes, parámetros y temporales con identificadores estables.
- Almacenamiento: variables locales, campos y elementos de lista diferenciados
  de sus valores. Identidad derivada del símbolo, no de su nombre visible.
- Instrucciones: constantes, copia, carga/almacenamiento, aritmética, comparación,
  conversiones, llamadas directas/virtuales y operaciones del runtime.
- Terminadores: salto, salto condicional y retorno.
- Funciones identificadas por clase declaradora y firma completa; conservar
  receptor implícito o explícito, parámetros y retorno.
- Ubicación de origen en las instrucciones para diagnósticos y navegación.
- Exportador textual determinista y verificador de tipos, destinos de salto,
  terminadores, firmas y definición de temporales antes de sus usos.

**Entregable:** `ModuloIr`, `FuncionIr`, `BloqueIr`, instrucciones y valores,
`ImpresorIr` y `VerificadorIr`, con pruebas de IR válido e inválido.

**Aceptación:** un IR estructuralmente inválido se rechaza antes de llegar al backend.

## H2. Primer tramo ejecutable: escalares y control

Antes de emitir llamadas de consola, implementar el registro de
`import runtime.Consola`, descriptores semánticos/AST y catálogo `LLAMAR_RUNTIME`
conforme a RC-1. Añadir comprobaciones y pruebas al modelo IR H1. La especificación
está en `ESPECIFICACION_RUNTIME_CONSOLA.md`.

Construir `GeneradorIr` y un intérprete Java de la IR para un subconjunto explícito:

- Literales escalares, locales, asignaciones simples y compuestas.
- Aritmética, comparación, menos unario, `not` y promociones numéricas.
- `if`, `else if`, `while`, `repeat (condición)` y retornos.
- Llamadas directas, parámetros y recursión dentro del alcance implementado.
- Saltos para implementar la política de cortocircuito acordada.
- Consola con lectura/escritura de escalares y cadenas básicas, canales
  inyectables y creación de la clase integrada (sin exigir todos los objetos
  del usuario). El subconjunto pendiente se diagnostica explícitamente.

Los destinos de asignaciones compuestas deben calcularse una sola vez. Por
 ejemplo, en `obj.f()[indice()] += valor()`, receptor e índice no deben duplicar
sus efectos. Las construcciones pendientes se rechazan explícitamente; nunca
se omiten ni producen una salida presentada como completa.

**Entregable:** programas de prueba ejecutados por el intérprete y su IR verificable.

**Aceptación:** resultados esperados de expresiones, rutas, llamadas y efectos;
pruebas específicas de evaluación única y cortocircuito.

## H3. Completar la IR y el runtime del lenguaje

Completado para generación y ejecución de referencia. Implementación y alcance
en `IR_EJECUTABLE_H3.md`; la validación nativa corresponde a H4/H5.

Añadir todo el comportamiento restante:

- Cadenas, concatenación y conversiones a cadena.
- Listas vacías, literales anidados, acceso, modificación y longitud.
- `repeat each` y `Exist`, incluyendo promociones del elemento iterado sin
  convertir una lista mutable completa.
- Objetos, campos, herencia, inicializadores y `new` sin argumentos.
- Llamadas virtuales y sobrescritura según el tipo dinámico del receptor;
  conservar la firma que resolvió el semántico.
- Imports como composición de unidades compiladas en un único módulo.
- Errores de ejecución definidos en H0 y errores de consola de RC-1.
- Completar formateadores de impresión para listas/objetos y reglas de UTF-8,
  lectura acotada, EOF y rangos usando los oráculos de consola.

Separar las operaciones abstractas del runtime en la IR de su representación
física: el intérprete puede usar objetos Java; el backend debe cumplir el mismo
contrato con su propio layout y funciones auxiliares.

**Entregable:** cobertura de todas las construcciones semánticas vigentes en
el generador y el intérprete, con inventario explícito de nodos soportados.

**Aceptación:** ningún nodo válido queda ignorado; tipos, aliasing, inicialización,
privacidad de campos heredados y despacho producen el comportamiento acordado.

## H4. Layout y convención de llamadas

Completado: offsets de campos/frames, slots virtuales y argumentos System V
en la proyección atómica; strings/constantes/descriptores y helpers/adaptadores
C en `RUNTIME_NATIVO_H4.md`. El soporte por módulo exporta NASM, y las pruebas
enlazan los puentes contra la biblioteca real. Los cuerpos de métodos fuente
y la entrada ejecutable se emiten en H5.

Definir para el destino elegido:

- Tamaños, alineación y ubicación de locales, parámetros y temporales.
- Layout de objetos y campos heredados, incluidos campos distintos con igual nombre.
- Layout de listas y cadenas y representación de `null`.
- Tablas de métodos virtuales: posiciones estables por firma y reemplazo por override.
- Paso del receptor, argumentos y retorno; marco de pila y registros preservados.
- Nombres de funciones únicos para clases y sobrecargas.
- Envoltorio de entrada y funciones de asignación, inicialización y runtime.
- Implementar los helpers nativos de RC-1 y el handle RtString conforme a
  `runtime/nativo/include/rt_console.h`; declarar efectos y verificar ABI.

**Entregable:** `LayoutPrograma`, contrato de llamadas y runtime nativo mínimo.

**Aceptación:** pruebas de offsets, firmas, alineación y slots de despacho; los
accesos y llamadas usan símbolos ya resueltos, sin repetir análisis semántico.

## H5. Backend de ensamblador y enlace

El cierre de H5 ahora exige ambos perfiles del contrato multiplataforma.
La implementación ya incorpora ABI y port del runtime Windows; su validación
cruzada bajo Wine no reemplaza la aceptación nativa Windows. Las vistas didácticas no participan
en la generación de artefactos ejecutables.


Implementado `GeneradorEnsamblador` sobre IR verificada y layout validado, con
`AdaptadorPlataformaNasm` y núcleo común; detalle en `GENERACION_NATIVA_H5.md`:

1. Entrada, constantes, operaciones escalares, saltos, llamadas directas y retorno.
2. Conversiones y operaciones de punto flotante.
3. Cadenas, listas, objetos, comprobaciones y llamadas al runtime.
4. Llamadas virtuales y todas las construcciones restantes.

La primera versión puede almacenar temporales en pila y priorizar corrección.
El ensamblado y el enlace deben ser pasos separados: exportar `.asm` NASM
no significa haber construido ni probado un ejecutable.

**Entregable:** ensamblador completo y ejecutable para el destino acordado.

**Aceptación:** ejecución con resultados equivalentes al intérprete y a los
oráculos de prueba. Los fallos del ensamblador/enlazador se muestran con su causa.

## H6. Integración en la aplicación y regresión

**Implementado:** el flujo gráfico incluye IR, ASM, selección de destino/entrada,
exportación y construcción por etapas en segundo plano; ejecución nativa con
consola y código de salida. Conserva instantáneas y bloquea acciones desactualizadas.
`PruebasH6` amplía la regresión y `PruebasH6Nativas` prueba herramientas reales,
JAR aislado y canales/plazo/cancelación. Procedimiento y límites en
`INTEGRACION_NATIVA_H6.md`. La aceptación manual GUI y Windows real sigue pendiente.

- Integrar la generación con el análisis del proyecto completo.
- Vistas de IR y ensamblador; exportación de ambos artefactos.
- Bloquear generación con errores o resultados desactualizados tras editar.
- Ejecutar herramientas externas en segundo plano con plazo, captura de salida
  y argumentos separados; conservar la respuesta de la interfaz.
- Presentar por separado: IR generada, ensamblador generado, ensamblado/enlace
  exitosos y ejecución comprobada.
- Ampliar el empaquetado para incluir runtime y documentar herramientas externas.

**Entregable:** flujo reproducible desde fuente hasta artefactos y ejecutable.

**Aceptación:** suites actuales, nuevas suites de generación/runtime y pruebas
manuales pasan; ningún proyecto parcial se exporta como ejecutable válido.

## H7. Optimización posterior

Implementado de forma opcional sobre la línea base H5/H6. Activa **Optimizar H7**
en la GUI o `--optimizar` en CLI. Las IR originales se conservan y el resultado
se vuelve a verificar; detalle y límites en `OPTIMIZACION_H7.md`.

- Propagación y plegado de constantes conforme a los rangos y errores de H0.
- Eliminación de código muerto sin perder efectos de llamadas ni errores observables.
- Simplificación de saltos y, después, asignación de registros.
- SSA únicamente si una optimización concreta lo justifica.

**Aceptación:** mismas salidas, efectos y errores de ejecución antes y después
de cada optimización. La optimización no es requisito para cerrar la primera
versión completa del generador.

## Pruebas transversales

- Reutilizar los programas válidos de las suites semánticas como corpus,
  añadiendo resultados esperados de ejecución; aceptación semántica no equivale
  a que un caso ya tenga un oráculo de generación.
- Cubrir evaluación única, cortocircuito, retornos, recursión, aliasing de listas,
  campos base/derivados, promociones y despacho virtual con efectos observables.
- Comparar intérprete IR y ejecutable con límites de tiempo. Los ciclos infinitos
  de programas válidos son un posible comportamiento, distinto de un bloqueo
  del compilador.
- Probar que entradas malformadas o semánticamente inválidas no generan IR válida.
- Comparar instrucciones/estructura y resultados, evitando pruebas que dependan
  únicamente de números arbitrarios de temporales o etiquetas.
- Usar la biblioteca Consola de RC-1 cuando esté implementada, con stdin/stdout
  inyectables y oráculos compartidos entre intérprete/backend. Complementar con
  inspección de estado y harness, sin inventar comandos exclusivos para pruebas.

## Organización propuesta

Mantener la organización por función y el paquete por defecto actual:

```text
untitled/src/intermedio/   modelo IR, generación, impresión y verificación
untitled/src/runtime/      contrato de operaciones e intérprete de referencia
untitled/src/ensamblador/  layout y backend del destino elegido
untitled/src/pruebas/      pruebas IR, runtime y ejecutables
runtime/nativo/           contrato ABI y futuros helpers C
runtime/especificacion/   oráculos declarativos de consola RC-1
```

Los componentes H0–H5 y la visualización de IR ya existen. Sigue la aceptación
manual de la GUI y nativa Windows. H6 integra ASM/herramientas en la GUI.
Evitar una reorganización adicional del frontend como requisito
para iniciar la generación.

## Orden y primeros entregables

```text
H0 → H1 → H2 → H3 → H4 → H5 → H6 → H7 opcional
```

El primer hito implementable es **contrato de ejecución + IR tipada mínima**.
Después se completa un tramo pequeño de fuente a IR ejecutada antes de ampliar
el runtime. Puede adelantarse un backend escalar tras H2/H4 para validar el
destino, pero no se declara completo hasta cubrir H3 y el runtime nativo.

H0 cerró plataforma/ensamblador y modelo de valores/memoria en
`CONTRATO_EJECUCION.md`. Las estimaciones de calendario se hacen tras esas decisiones
y el primer tramo ejecutable; este documento establece dependencias y criterios,
no fechas de entrega inventadas.
