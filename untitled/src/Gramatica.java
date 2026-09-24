import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fuente ejecutable de la CFG canónica. El documento GRAMATICA_CANONICA.md
 * explica estas mismas reglas; cualquier cambio de lenguaje debe actualizar
 * ambos archivos y sus pruebas de regresión en el mismo cambio.
 */
class Gramatica {
    private final Set<String> terminales = new LinkedHashSet<>();
    private final Set<String> noTerminales = new LinkedHashSet<>();
    private final List<String> producciones = new ArrayList<>();
    private final String simboloInicial = "PROGRAMA";

    Gramatica() {
        cargarTerminales();
        cargarNoTerminales();
        cargarProducciones();
    }

    private void cargarTerminales() {
        terminales.addAll(Arrays.asList(
                "import", "class", "extends", "public", "private", "main", "void", "return",
                "if", "else", "repeat", "while", "each", "in", "Exist", "new", "var", "let",
                "int", "float", "string", "bool", "list", "true", "false", "null", "not",
                "ID", "ENTERO", "FLOAT", "CADENA", "EOF",
                "{", "}", "(", ")", "[", "]", ";", ",", ".",
                "=", "+=", "-=", "*=", "/=", "==", "!=", "<", "<=", ">", ">=",
                "+", "-", "*", "/", "&&", "||"
        ));
    }

    private void cargarNoTerminales() {
        noTerminales.addAll(Arrays.asList(
                "PROGRAMA", "LISTA_IMPORTS", "RUTA", "RUTA_RESTO", "BLOQUE_GLOBAL", "LISTA_CLASES",
                "CLASE", "MODIFICADOR_OPT", "HERENCIA_OPT", "LISTA_MIEMBROS", "MIEMBRO",
                "MIEMBRO_RESTO", "MIEMBRO_TIPADO_RESTO", "PARAMETROS_OPT", "LISTA_PARAMETROS", "PARAMETRO",
                "TIPO", "TIPO_BASE", "BLOQUE", "LISTA_SENTENCIAS", "SENTENCIA", "DECLARACION",
                "INICIALIZACION_OPT", "SENTENCIA_TRAS_ID", "FINAL_SENTENCIA_ID", "OPERADOR_ASIGNACION",
                "SENT_IF", "ELSE_OPT", "SENT_WHILE", "SENT_REPEAT", "REPEAT_RESTO", "SENT_EXIST",
                "SENT_RETURN", "EXPRESION_OPT", "EXPRESION", "EXPR_OR", "EXPR_OR_RESTO", "EXPR_AND",
                "EXPR_AND_RESTO", "EXPR_NOT", "EXPR_COMPARACION", "EXPR_COMPARACION_RESTO",
                "OPERADOR_COMPARACION", "EXPR_SUMA", "EXPR_SUMA_RESTO", "EXPR_PRODUCTO",
                "EXPR_PRODUCTO_RESTO", "EXPR_UNARIA", "PRIMARIO", "SUFIJOS", "SUFIJO",
                "ARGUMENTOS_OPT", "LISTA_ARGUMENTOS"
        ));
    }

    private void cargarProducciones() {
        producciones.addAll(Arrays.asList(
                "P.1  PROGRAMA → LISTA_IMPORTS BLOQUE_GLOBAL EOF",
                "P.2  LISTA_IMPORTS → import RUTA ; LISTA_IMPORTS | ε",
                "P.3  RUTA → ID RUTA_RESTO",
                "P.4  RUTA_RESTO → . ID RUTA_RESTO | ε",
                "P.5  BLOQUE_GLOBAL → { LISTA_CLASES }",
                "P.6  LISTA_CLASES → CLASE LISTA_CLASES | ε",
                "C.1  CLASE → MODIFICADOR_OPT class ID HERENCIA_OPT { LISTA_MIEMBROS }",
                "C.2  MODIFICADOR_OPT → public | private | ε",
                "C.3  HERENCIA_OPT → extends ID | ε",
                "C.4  LISTA_MIEMBROS → MIEMBRO LISTA_MIEMBROS | ε",
                "C.5  MIEMBRO → MODIFICADOR_OPT MIEMBRO_RESTO",
                "C.6  MIEMBRO_RESTO → main ( ) BLOQUE | void ID ( PARAMETROS_OPT ) BLOQUE | TIPO ID MIEMBRO_TIPADO_RESTO",
                "C.7  MIEMBRO_TIPADO_RESTO → ( PARAMETROS_OPT ) BLOQUE | INICIALIZACION_OPT ;",
                "C.8  PARAMETROS_OPT → PARAMETRO LISTA_PARAMETROS | ε",
                "C.9  LISTA_PARAMETROS → , PARAMETRO LISTA_PARAMETROS | ε",
                "C.10 PARAMETRO → TIPO ID",
                "T.1  TIPO → TIPO_BASE | ID",
                "T.2  TIPO_BASE → int | float | string | bool | list < TIPO >",
                "S.1  BLOQUE → { LISTA_SENTENCIAS }",
                "S.2  LISTA_SENTENCIAS → SENTENCIA LISTA_SENTENCIAS | ε",
                "S.3  SENTENCIA → DECLARACION | ID SENTENCIA_TRAS_ID | SENT_IF | SENT_WHILE | SENT_REPEAT | SENT_EXIST | SENT_RETURN",
                "S.4  DECLARACION → var ID = EXPRESION ; | let ID = EXPRESION ; | TIPO_BASE ID INICIALIZACION_OPT ;",
                "S.5  INICIALIZACION_OPT → = EXPRESION | ε",
                "S.6  SENTENCIA_TRAS_ID → ID INICIALIZACION_OPT ; | OPERADOR_ASIGNACION EXPRESION ; | ; | SUFIJO SUFIJOS FINAL_SENTENCIA_ID",
                "S.7  FINAL_SENTENCIA_ID → OPERADOR_ASIGNACION EXPRESION ; | ;",
                "S.8  OPERADOR_ASIGNACION → = | += | -= | *= | /=",
                "I.1  SENT_IF → if ( EXPRESION ) BLOQUE ELSE_OPT",
                "I.2  ELSE_OPT → else BLOQUE | else SENT_IF | ε",
                "CL.1 SENT_WHILE → while ( EXPRESION ) BLOQUE",
                "CL.2 SENT_REPEAT → repeat REPEAT_RESTO",
                "CL.3 REPEAT_RESTO → ( EXPRESION ) BLOQUE | each ( TIPO ID in EXPRESION ) BLOQUE",
                "I.3  SENT_EXIST → Exist ( EXPRESION ) BLOQUE",
                "R.1  SENT_RETURN → return EXPRESION_OPT ;",
                "R.2  EXPRESION_OPT → EXPRESION | ε",
                "E.1  EXPRESION → EXPR_OR",
                "E.2  EXPR_OR → EXPR_AND EXPR_OR_RESTO",
                "E.3  EXPR_OR_RESTO → || EXPR_AND EXPR_OR_RESTO | ε",
                "E.4  EXPR_AND → EXPR_NOT EXPR_AND_RESTO",
                "E.5  EXPR_AND_RESTO → && EXPR_NOT EXPR_AND_RESTO | ε",
                "E.6  EXPR_NOT → not EXPR_NOT | EXPR_COMPARACION",
                "E.7  EXPR_COMPARACION → EXPR_SUMA EXPR_COMPARACION_RESTO",
                "E.8  EXPR_COMPARACION_RESTO → OPERADOR_COMPARACION EXPR_SUMA | ε",
                "E.9  OPERADOR_COMPARACION → == | != | < | <= | > | >=",
                "E.10 EXPR_SUMA → EXPR_PRODUCTO EXPR_SUMA_RESTO",
                "E.11 EXPR_SUMA_RESTO → + EXPR_PRODUCTO EXPR_SUMA_RESTO | - EXPR_PRODUCTO EXPR_SUMA_RESTO | ε",
                "E.12 EXPR_PRODUCTO → EXPR_UNARIA EXPR_PRODUCTO_RESTO",
                "E.13 EXPR_PRODUCTO_RESTO → * EXPR_UNARIA EXPR_PRODUCTO_RESTO | / EXPR_UNARIA EXPR_PRODUCTO_RESTO | ε",
                "E.14 EXPR_UNARIA → - EXPR_UNARIA | PRIMARIO",
                "E.15 PRIMARIO → ENTERO | FLOAT | CADENA | true | false | null | ( EXPRESION ) | new ID ( ARGUMENTOS_OPT ) | [ ARGUMENTOS_OPT ] | ID SUFIJOS",
                "E.16 SUFIJOS → SUFIJO SUFIJOS | ε",
                "E.17 SUFIJO → ( ARGUMENTOS_OPT ) | [ EXPRESION ] | . ID",
                "E.18 ARGUMENTOS_OPT → EXPRESION LISTA_ARGUMENTOS | ε",
                "E.19 LISTA_ARGUMENTOS → , EXPRESION LISTA_ARGUMENTOS | ε"
        ));
    }

    boolean esTipoDato(String lexema) {
        return Arrays.asList("int", "float", "string", "bool", "list").contains(lexema);
    }

    boolean esModificador(String lexema) {
        return lexema.equals("public") || lexema.equals("private");
    }

    boolean esOperadorAsignacion(String lexema) {
        return Arrays.asList("=", "+=", "-=", "*=", "/=").contains(lexema);
    }

    boolean esOperadorUnario(String lexema) {
        return lexema.equals("-");
    }

    String getSimboloInicial() { return simboloInicial; }
    Set<String> getTerminales() { return Collections.unmodifiableSet(terminales); }
    Set<String> getNoTerminales() { return Collections.unmodifiableSet(noTerminales); }
    List<String> getProducciones() { return Collections.unmodifiableList(producciones); }

    List<String[]> construirTablaGramatical() {
        List<String[]> filas = new ArrayList<>();
        for (String terminal : terminales) filas.add(new String[] { terminal, "VT", "Terminal de la CFG canónica" });
        for (String noTerminal : noTerminales) filas.add(new String[] { noTerminal, "VN", "No terminal de la CFG canónica" });
        filas.add(new String[] { simboloInicial, "S", "Símbolo inicial" });
        return filas;
    }
}
