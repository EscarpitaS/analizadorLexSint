# Auditoría de conformidad con la gramática canónica

La referencia es `GRAMATICA_CANONICA.md`. No se ampliaron sus producciones.

## Discrepancias corregidas

- Una cadena interrumpida por LF se emitía como token `STRING` antes de
  reportar el error. Ahora se informa la cadena incompleta y no se emite un
  literal válido.
- CR se trataba como espacio dentro de cadenas y comentarios. Se normalizan
  LF, CRLF y CR como límites de línea, preservando línea y columna lógicas.
- El ejemplo `programa_demo.txt` retornaba un entero desde `main`, que tiene
  retorno `void`. Ahora usa `return;`. Se corrigió también el ejemplo destinado
  a demostrar exclusivamente un error sintáctico.
- El accesorio `Lexico.getImportaciones()` admitía palabras clave en rutas,
  identificadores sin puntos e imports fuera del encabezado. Ahora reconoce
  únicamente `import ID ('.' ID)* ';'` al inicio del archivo. La carga de
  dependencias sigue basándose en los imports aceptados por el parser.
- Repetir el análisis con la misma instancia acumulaba tokens o reutilizaba
  la posición final del parser. Ambas fases reinician su estado.

## Aclaraciones documentales

Se explicitaron el alfabeto ASCII de identificadores, sensibilidad a mayúsculas,
la equivalencia `CADENA`/`STRING`, los finales de línea y la ausencia de escapes
para comillas. `for`, `decimal`, `and` y `or` no son palabras reservadas: lo que
queda excluido son las construcciones, tipos primitivos y operadores adicionales.

Se documentó la precedencia exacta de `not` y que los sufijos solo parten de
`ID`. Por ejemplo, `obj.f()[0]` pertenece a la BNF, mientras que
`new Clase().f()` y `(obj).f()` no. No se habilitaron estas extensiones.

## Verificación

- `tools/verificar_gramatica.py` compara las 53 reglas del Markdown con las
  alternativas de `Gramatica.java`, expandiendo los grupos de la notación BNF.
- `PruebasConformidadGramatica` comprueba vocabulario, operadores, delimitadores,
  cadenas, imports, expresiones, tipos, restricciones estructurales y la fase
  que debe rechazar los casos inválidos. Forma parte de `PruebasRegresion`.
- La suite completa pasa compilando con `javac --release 11`.
- El ejemplo válido produce cero errores; el ejemplo de error sintáctico produce
  un error sintáctico y ninguno semántico.

La comparación documental verifica las producciones; los casos ejecutables
verifican el comportamiento del parser y del motor. Esta auditoría no equivale
a una demostración formal de corrección para todas las entradas posibles.
