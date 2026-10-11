# Auditoría de consistencia de IR

Fecha: 10 de octubre de 2026.

Se revisaron AST decorado → IR tipada → cuádruplos físicos, verificadores,
layout/ABI Linux, datos nativos, ambos intérpretes y las vistas didácticas.
No se modificó la estructura de los modelos ni se añadieron operaciones IR.

## Discrepancias corregidas

- **Despacho virtual:** el verificador físico aceptaba omitir una sobrescritura
  o un método público de la vtable. Una IR aceptada podía retornar 1 cuando el
  programa original retornaba 2. Ahora exige la implementación más derivada,
  cobertura de métodos públicos y compatibilidad de sobrescrituras.
- **Receptor de CALL_INDIRECT:** aceptaba reutilizar el puntero cargado desde
  otro objeto de igual tipo estático. Ahora enlaza la procedencia del puntero con
  el receptor preparado en PARAM; las copias de punteros y de referencias de
  objeto conservan esa procedencia, incluidas las promociones a clase base.
  El análisis funciona también con bloques listados después de sus usos pero
  dominantes en el CFG.
- **Direcciones internas:** vtables y punteros de función podían usarse como
  celdas int. Ahora se rechazan esas cargas/escrituras; COPY continúa permitido
  como copia del valor de dirección, sin desreferenciarlo.
- **Frontera física:** se comprueban clases desconocidas dentro de tipos anidados,
  tipos de almacenamiento, categorías de parámetros/temporales, etiquetas
  globales y correspondencia entre tamaño de clase y ranuras.
- **Descriptores de campos:** el soporte nativo emitía su vector según el orden
  del diccionario de tipos. Ahora lo ordena por offsets físicos, de forma que
  cambiar el orden del diccionario no cambia la correspondencia campo/tipo.
- **Formateo de float:** la IR tipada dependía de Double.toString del JDK anfitrión,
  mientras runtime/vista didáctica usaban Java 11. Ahora las vistas textuales
  coinciden: el valor binary64 de 1e23 se muestra como 9.999999999999999E22.
  Los bits de las constantes no cambian.
- **Identidad visible:** una variable L0 podía confundirse con una etiqueta L0;
  ahora recibe el sufijo de símbolo, conservando los literales string intactos.

## Verificación

`PruebasConsistenciaIr`, integrada en `PruebasRegresion`, comprueba los casos
anteriores, el rechazo previo a impresión/datos nativos/ejecución, reordenación
válida de cuerpos y bloques, asignaciones con efectos sobre el destino/RHS,
conversiones de listas a texto antes del siguiente operando y cortocircuito.
Incluye 64 expresiones aritméticas anidadas reproducibles, con resultados
independientes calculados en int64, límites enteros, NaN y ceros con signo.
Ambos intérpretes se comparan con expectativas independientes, además de
comprobar que la presentación no modifica sus módulos.

Resultado de la revisión: regresión completa aprobada, incluidas 93 comprobaciones
adicionales de consistencia. Los puentes H4 se ensamblaron y ejecutaron con
NASM/ELF64 y con GNU as, enlazados contra el runtime C original. Esto valida el
soporte de H4; no sustituye la ejecución de programas completos pendiente en H5.

```sh
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasConsistenciaIr
java -Djava.awt.headless=true -cp build/AnalizadorLexSint.jar PruebasRegresion
```

La suite cubre las construcciones actuales de la gramática. No constituye una
prueba formal de ausencia de bugs en programas arbitrarios. El presupuesto de
memoria del harness mantiene sus límites documentados en IR_EJECUTABLE_H3.md.

## Cierre y siguiente fase

La representación y ejecución de referencia H1–H3, junto con layout/datos/puentes
Linux H4, quedan listas para avanzar al backend H5. El cierre se refiere a esos
componentes y al lenguaje actual; H5 todavía debe emitir y ejecutar programas
completos, verificando las guardas de acceso, errores, división, ABI y efectos
en código nativo. Las vistas pedagógicas siguen siendo proyecciones de consulta.

Windows conserva el contrato ampliado, pero su proyección ABI y runtime siguen
pendientes según CONTRATO_MULTIPLATAFORMA.md. Esta auditoría no implementa ni
certifica ejecución Windows.

## Evolución posterior: H5

Las afirmaciones de alcance anteriores corresponden al cierre de esta auditoría.
H5 incorpora después perfiles físicos Linux/Windows y el backend completo;
estado y verificaciones en [GENERACION_NATIVA_H5.md](GENERACION_NATIVA_H5.md).
