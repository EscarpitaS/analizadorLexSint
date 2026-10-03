import java.util.*;

/** Casos de frontera derivados de GRAMATICA_CANONICA.md, separados por fase. */
public class PruebasConformidadGramatica {
    public static void main(String[] args) {
        ejecutar();
        System.out.println("OK: conformidad lexica, sintactica y semantica con la gramatica canonica.");
    }

    static void ejecutar() {
        String palabras = "import class extends public private main void return if else while repeat each in Exist new var let int float string bool list not true false null";
        Lexico vocabulario = lexer(palabras);
        for (Token token : vocabulario.getTokens()) {
            if (token.tipo != tipoToken.EOF) exigir(token.tipo == tipoToken.PALABRA_CLAVE, "Palabra no reservada: " + token);
        }
        String operadores = "= += -= *= /= == != < <= > >= + - * / && ||";
        for (Token token : lexer(operadores).getTokens()) {
            if (token.tipo != tipoToken.EOF) exigir(token.tipo == tipoToken.OPERADOR, "Operador incorrecto: " + token);
        }
        for (Token token : lexer("{}()[];,.").getTokens()) {
            if (token.tipo != tipoToken.EOF) exigir(token.tipo == tipoToken.DELIMITADOR, "Delimitador incorrecto: " + token);
        }
        for (String id : new String[]{"a", "Z9", "Existencia", "exist", "for", "decimal", "and", "or"}) {
            exigir(lexer(id).getTokens().get(0).tipo == tipoToken.IDENTIFICADOR, "Identificador incorrecto: " + id);
        }
        for (String fuente : new String[]{"1nombre", "_x", "niño", "!", "&", "|", ":", "1.", "\"abierto"}) {
            Lexico l = new Lexico(fuente); l.tokenizar(); exigir(l.hasErrors(), "Debe fallar en léxico: " + fuente);
        }
        for (String salto : new String[]{"\n", "\r\n", "\r"}) {
            Lexico l = new Lexico("\"abierto" + salto + "int x;"); l.tokenizar();
            exigir(l.hasErrors(), "Cadena multilinea aceptada.");
            exigir(l.getTokens().stream().noneMatch(t -> t.tipo == tipoToken.STRING), "Cadena incompleta emitida como válida.");
            exigir(l.getTokens().get(0).lexema.equals("int") && l.getTokens().get(0).linea == 2, "No recupera la línea siguiente.");
            Lexico comentario = lexer("// ignorar : @ !" + salto + "int");
            exigir(comentario.getTokens().get(0).lexema.equals("int") && comentario.getTokens().get(0).linea == 2,
                    "El comentario no termina en el salto de línea.");
        }
        exigir(lexer("\"\" \"texto : // ñ\"").getTokens().size() == 3, "Cadenas válidas no reconocidas.");
        exigir(lexer("// comentario sin salto").getTokens().size() == 1, "Comentario al EOF no descartado.");
        for (String expr : new String[]{"1", "3.14", "\"hola\"", "true", "false", "null", "not a < b", "not not true",
                "- -1", "1 + 2 * 3", "a.f(1, [2])[0].x", "new Persona(1)", "[]", "[[1], []]", "(1 < 2) == true"}) {
            sintaxis("var x = " + expr + ";", false);
        }
        for (String expr : new String[]{"a < b < c", "a == b == c", "new Persona().f()", "(a).f()", "[1][0]",
                "+1", "1 + not true", "[1,]", "f(1,)", "1 and 2", ".5", "1e2"}) {
            Lexico l = new Lexico(programa("var x = " + expr + ";")); l.tokenizar();
            if (l.hasErrors()) continue;
            AnalizadorSintactico parser = parser(l); exigir(parser.hasErrors(), "Expresión fuera de BNF aceptada: " + expr);
        }
        for (String cuerpo : new String[]{"var x;", "let x;", "void x;", "list<void> xs;", "for (true) { }",
                "each (int x in [1]) { }", "repeat while (true) { }", "else { }", "{ int x; }", ";"}) sintaxis(cuerpo, true);
        for (String cuerpo : new String[]{"Persona p;", "list<list<int>> xs;", "repeat each (Persona p in xs) { }",
                "repeat (true) { }", "Exist(xs) { }", "if (true) { } else if (false) { } else { }", "x;", "f() = 1;"}) sintaxis(cuerpo, false);
        String[] invalidos = {
                "{ class Main { main() { } } }", "{ class Main { private main() { } } }",
                "{ class Main { public void main() { } } }", "{ class Main { public main(int x) { } } }",
                "{ class Main { var x = 1; public main() { } } }",
                "{ class Main { void f(void x) { } public main() { } } }",
                "{ class Main { Main() { } public main() { } } }",
                "import java.; { class Main { public main() { } } }",
                "import java.class; { class Main { public main() { } } }",
                "{}", "class Main { public main() { } }", "{ class Main { public main() { } } } {}"
        };
        for (String fuente : invalidos) exigir(parser(lexer(fuente)).hasErrors(), "Programa fuera de BNF aceptado: " + fuente);
        semantica("{ class Persona { } class Main { public main() { Persona p = new Persona(); } } }", false);
        semantica("{ class Persona { } class Main { public main() { Persona p = new Persona(1); } } }", true);
        semantica(programa("int x = 1; x;"), true);
        semantica(programa("return 1;"), true);
        semantica(programa("return;"), false);
        semantica(programa("bool b = not 1 < 2;"), false);
        semantica(programa("bool b = (not 1) < 2;"), true);
        Lexico imports = lexer("import uno.dos; import tres; { }");
        exigir(imports.getImportaciones().size() == 2, "No extrae rutas válidas.");
        exigir(lexer("import uno.class; { }").getImportaciones().isEmpty(), "Acepta keyword dentro de Ruta.");
        exigir(lexer("{ import uno; }").getImportaciones().isEmpty(), "Extrae un import fuera del encabezado.");
        Lexico repetido = lexer(programa("int x = 1;"));
        String tokens = repetido.getTokens().toString(); repetido.tokenizar();
        exigir(tokens.equals(repetido.getTokens().toString()), "Lexer no reproducible.");
        AnalizadorSintactico p = parser(repetido); String ast = p.getArbol().imprimir(); p.analizar();
        exigir(!p.hasErrors() && ast.equals(p.getArbol().imprimir()), "Parser no reproducible.");
        Set<String> terminales = new Gramatica().getTerminales();
        for (Token token : lexer(palabras + " " + operadores + " {}()[];,. nombre 1 1.2 \"s\"").getTokens()) {
            String terminal;
            switch (token.tipo) {
                case IDENTIFICADOR: terminal = "ID"; break;
                case STRING: terminal = "CADENA"; break;
                case ENTERO: terminal = "ENTERO"; break;
                case FLOAT: terminal = "FLOAT"; break;
                case EOF: terminal = "EOF"; break;
                default: terminal = token.lexema;
            }
            exigir(terminales.contains(terminal), "Token sin terminal canónico: " + terminal);
        }
    }
    private static String programa(String cuerpo) { return "{ class Main { public main() { " + cuerpo + " } } }"; }
    private static Lexico lexer(String fuente) {
        Lexico l = new Lexico(fuente); l.tokenizar(); exigir(!l.hasErrors(), "Error léxico inesperado: " + l.getErrors()); return l;
    }
    private static AnalizadorSintactico parser(Lexico l) {
        AnalizadorSintactico p = new AnalizadorSintactico(l.getTokens(), new Gramatica()); p.analizar(); return p;
    }
    private static void sintaxis(String cuerpo, boolean error) {
        exigir(parser(lexer(programa(cuerpo))).hasErrors() == error, "Sintaxis inesperada: " + cuerpo);
    }
    private static void semantica(String fuente, boolean error) {
        AnalizadorSintactico p = parser(lexer(fuente)); exigir(!p.hasErrors(), "Caso semántico con sintaxis inválida.");
        Semantico s = new Semantico(); s.analizar(p.getArbol()); exigir(s.hasErrors() == error, "Semántica inesperada: " + fuente);
    }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
