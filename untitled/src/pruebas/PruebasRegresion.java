/**
 * Pruebas sin dependencias externas para la gramática canónica.
 * Ejecutar con: java -cp <directorio-de-clases> PruebasRegresion
 */
public class PruebasRegresion {
    public static void main(String[] args) {
        tokenizaSecuenciaCanonica();
        tokenizaOperadoresCompuestos();
        reportaErroresLexicosConUbicacion();
        reportaFloatSinDecimalesAlFinal();
        compruebaContratoSemanticoBasico();
        compruebaTablaSimbolosJerarquica();
        compruebaPrimeraPasadaSemantica();
        compruebaSegundaPasadaSemantica();
        compruebaTiposSemanticos();
        compruebaAsignacionCompuestaSemantica();
        compruebaAnotacionDeTipos();
        compruebaInicializadoresDeCampo();
        compruebaFlujoDeRetorno();
        compruebaConstructoresImplicitos();
        PruebasSemanticas.ejecutar();
        PruebasPresentacionSemantica.ejecutar();
        PruebasConformidadGramatica.ejecutar();
        PruebasAstDecorado.ejecutar();
        PruebasEstadosAst.ejecutar();
        PruebasEditorCodigo.ejecutar();
        PruebasNavegacionErrores.ejecutar();
        PruebasAutocompletado.ejecutar();
        PruebasComposicionSemantica.ejecutar();
        PruebasEntradasMalformadas.ejecutar();
        acepta("programa minimo", "{ public class Main { public main() { } } }");
        conservaUbicacionFuente();
        acepta("sufijos y expresiones", "{ public class Main { public main() { "
                + "list<int> xs = [1, 2]; xs[0] += 1; objeto.metodo(xs[0]); "
                + "bool ok = not false && 1 < 2; } } }");
        acepta("clases y control de flujo", "{ "
                + "public class Persona { } "
                + "public class Main extends Persona { "
                + "int total; "
                + "void sumar(int n, list<int> valores) { "
                + "if (n > 0) { total += n; } else if (n == 0) { return; } "
                + "while (n > 0) { n -= 1; } "
                + "repeat each (int valor in valores) { total += valor; } "
                + "Exist(valores) { total = total + 1; } "
                + "} "
                + "public main() { Persona persona = new Persona(); persona.configurar().activar(); } "
                + "} "
                + "}");
        acepta("imports", "import java.util.List; { public class Main { public main() { } } }");

        rechazaLexico("identificador inicia con digito", "{ public class Main { public main() { int 1valor = 0; } } }");
        rechazaLexico("dos puntos fuera del alfabeto", "{ public class Main { public main() { if (true): { } } } }");
        rechazaSintactico("sin bloque global", "public class Main { public main() { } }");
        rechazaSintactico("sin main publico", "{ public class Main { private main() { } } }");
        rechazaSintactico("ruta import invalida", "import java.; { public class Main { public main() { } } }");
        rechazaSintactico("comparacion encadenada", "{ public class Main { public main() { bool ok = a < b < c; } } }");
        rechazaSintactico("repeat while fuera de la CFG", "{ public class Main { public main() { repeat while (true) { } } } }");
        rechazaSintactico("coma final en argumentos", "{ public class Main { public main() { f(1,); } } }");
        rechazaSintactico("coma final en parametros", "{ public class Main { void f(int x,) { } public main() { } } }");
        rechazaSintactico("coma final en arreglo", "{ public class Main { public main() { list<int> xs = [1,]; } } }");
        recuperaDosExpresionesFaltantes();
        recuperaListasSinComa();
        recuperaImportsMalformados();
        recuperaMiembroClaseMalformado();
        recuperaCabeceraCondicionalMalformada();
        recuperaCabeceraWhileMalformada();
        recuperaCabeceraExistMalformada();
        recuperaCabeceraRepeatMalformada();
        recuperaCabeceraRepeatEachMalformada();
        recuperaLimiteDeSentencia();
        recuperaLimitesRestantes();
        recuperaCabeceraMetodoMalformada();
        recuperaCabeceraMainMalformada();

        System.out.println("OK: pruebas de regresion lexica, sintactica y semantica aprobadas.");
    }

    private static void acepta(String nombre, String fuente) {
        Resultado resultado = analizar(fuente);
        comprobar(!resultado.lexico.hasErrors(), nombre + ": se esperaban cero errores lexicos.");
        comprobar(!resultado.sintactico.hasErrors(), nombre + ": se esperaban cero errores sintacticos.");
    }

    private static void tokenizaSecuenciaCanonica() {
        Lexico lexico = new Lexico("int x = 12; float pi = 3.14; bool ok = true && false; // comentario\n");
        lexico.tokenizar();
        comprobar(!lexico.hasErrors(), "Lexer: la secuencia canónica no debe producir errores.");

        String[] esperados = {
                "PALABRA_CLAVE:int", "IDENTIFICADOR:x", "OPERADOR:=", "ENTERO:12", "DELIMITADOR:;",
                "PALABRA_CLAVE:float", "IDENTIFICADOR:pi", "OPERADOR:=", "FLOAT:3.14", "DELIMITADOR:;",
                "PALABRA_CLAVE:bool", "IDENTIFICADOR:ok", "OPERADOR:=", "PALABRA_CLAVE:true",
                "OPERADOR:&&", "PALABRA_CLAVE:false", "DELIMITADOR:;", "EOF:<EOF>"
        };
        comprobar(lexico.getTokens().size() == esperados.length,
                "Lexer: cantidad de tokens distinta a la esperada.");
        for (int i = 0; i < esperados.length; i++) {
            Token token = lexico.getTokens().get(i);
            String recibido = token.tipo + ":" + token.lexema;
            comprobar(esperados[i].equals(recibido),
                    "Lexer: token " + i + " esperado " + esperados[i] + ", recibido " + recibido + ".");
        }
    }

    private static void tokenizaOperadoresCompuestos() {
        Lexico lexico = new Lexico("a == b != c <= d >= e += f -= g *= h /= i || j && k;");
        lexico.tokenizar();
        comprobar(!lexico.hasErrors(), "Lexer: los operadores compuestos no deben producir errores.");
        String[] operadores = { "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "||", "&&" };
        int indiceOperador = 0;
        for (Token token : lexico.getTokens()) {
            if (token.tipo == tipoToken.OPERADOR) {
                comprobar(indiceOperador < operadores.length,
                        "Lexer: se produjo un operador compuesto inesperado.");
                comprobar(operadores[indiceOperador].equals(token.lexema),
                        "Lexer: operador esperado " + operadores[indiceOperador] + ", recibido " + token.lexema + ".");
                indiceOperador++;
            }
        }
        comprobar(indiceOperador == operadores.length,
                "Lexer: faltan operadores compuestos en la secuencia tokenizada.");
    }

    private static void reportaErroresLexicosConUbicacion() {
        Lexico lexico = new Lexico("1valor\n:\n!\n&\n|\n\"sin cierre");
        lexico.tokenizar();
        comprobar(lexico.getErrors().size() == 6,
                "Lexer: se esperaban seis errores léxicos de borde.");
        for (int i = 0; i < lexico.getErrors().size(); i++) {
            compiladorErrores error = lexico.getErrors().get(i);
            comprobar(error.linea == i + 1 && error.columna == 1,
                    "Lexer: ubicación incorrecta para el error léxico de la línea " + (i + 1) + ".");
        }
    }

    private static void reportaFloatSinDecimalesAlFinal() {
        Lexico lexico = new Lexico("1.");
        lexico.tokenizar();
        comprobar(lexico.getErrors().size() == 1,
                "Lexer: '1.' al final del archivo debe reportar un decimal incompleto.");
        comprobar(lexico.getErrors().get(0).mensaje.equals("Punto decimal sin digitos despues"),
                "Lexer: el decimal incompleto debe conservar su diagnostico especifico.");
    }

    private static void compruebaContratoSemanticoBasico() {
        comprobar(Semantico.esAsignable(Semantico.FLOAT, Semantico.INT),
                "Semantico: int debe promoverse a float.");
        comprobar(!Semantico.esAsignable(Semantico.INT, Semantico.FLOAT),
                "Semantico: float no debe asignarse implicitamente a int.");
        comprobar(Semantico.esAsignable(Semantico.STRING, Semantico.NULL),
                "Semantico: null debe asignarse a tipos de referencia.");
        comprobar(Semantico.resultadoAritmetico(Semantico.INT, Semantico.FLOAT).equals(Semantico.FLOAT),
                "Semantico: una operacion numerica mixta debe producir float.");
        comprobar(Semantico.resultadoSuma(Semantico.STRING, Semantico.BOOL).equals(Semantico.STRING),
                "Semantico: '+' debe permitir concatenacion con string.");
        comprobar(Semantico.mutabilidadDeDeclaracion("let") == Semantico.Mutabilidad.INMUTABLE,
                "Semantico: let debe ser inmutable.");
    }

    private static void compruebaTablaSimbolosJerarquica() {
        Semantico.Contexto global = Semantico.Contexto.global();
        Semantico.Simbolo clase = new Semantico.Simbolo("Persona", Semantico.clase("Persona"),
                Semantico.RolSimbolo.CLASE, Semantico.Visibilidad.PUBLICO,
                Semantico.Mutabilidad.INMUTABLE, 1, 1, java.util.Collections.emptyList());
        comprobar(global.ambitoActual.declarar(clase), "Semantico: debe declarar la clase global.");

        Semantico.ClaseInfo persona = new Semantico.ClaseInfo(clase, global.ambitoActual, null);
        Semantico.Contexto claseCtx = global.conClase(persona);
        Semantico.Simbolo metodo = Semantico.Simbolo.metodo("saludar", Semantico.VOID,
                Semantico.Visibilidad.PUBLICO, java.util.Collections.singletonList(Semantico.STRING), 2, 1);
        comprobar(claseCtx.ambitoActual.declarar(metodo), "Semantico: debe declarar el método.");

        Semantico.Contexto metodoCtx = claseCtx.conMetodo(metodo);
        Semantico.Simbolo parametro = Semantico.Simbolo.variable("nombre", Semantico.STRING,
                Semantico.RolSimbolo.PARAMETRO, Semantico.Mutabilidad.MUTABLE, 2, 14);
        comprobar(metodoCtx.ambitoActual.declarar(parametro), "Semantico: debe declarar el parámetro.");
        Semantico.Contexto bloqueCtx = metodoCtx.conBloque();
        comprobar(bloqueCtx.ambitoActual.resolverValor("nombre") == parametro,
                "Semantico: un bloque debe resolver símbolos del método.");
        comprobar(bloqueCtx.ambitoActual.hayLocalVisible("nombre"),
                "Semantico: no se permite ocultar un parámetro aún visible.");
    }

    private static void compruebaPrimeraPasadaSemantica() {
        Resultado resultado = analizar("{ public class Main extends Persona { "
                + "Persona jefe; void saludar(string nombre) { } public main() { } } "
                + "class Persona { } }");
        comprobar(!resultado.sintactico.hasErrors(), "Semantico: el programa de declaración debe ser sintácticamente válido.");
        Semantico semantico = new Semantico();
        semantico.registrarDeclaraciones(resultado.sintactico.getArbol());
        comprobar(!semantico.hasErrors(), "Semantico: debe admitir clase base y tipo declarados después.");
        comprobar(semantico.getClase("Main") != null && semantico.getClase("Persona") != null,
                "Semantico: la primera pasada debe registrar todas las clases.");
        comprobar(semantico.getClase("Main").miembros.resolverValor("jefe") != null,
                "Semantico: la primera pasada debe registrar campos.");
        comprobar(semantico.getClase("Main").miembros.resolverMetodosLocales("saludar").size() == 1,
                "Semantico: la primera pasada debe registrar firmas de método.");

        Resultado repetido = analizar("{ class Main { int x; int x; public main() { } } }");
        Semantico semanticoRepetido = new Semantico();
        semanticoRepetido.registrarDeclaraciones(repetido.sintactico.getArbol());
        comprobar(semanticoRepetido.hasErrors(),
                "Semantico: debe detectar campos repetidos durante la primera pasada.");
    }

    private static void compruebaSegundaPasadaSemantica() {
        Resultado valido = analizar("{ class Main { int total; void sumar(int n) { total = n; } "
                + "public main() { int x = 1; sumar(x); } } }");
        Semantico semanticoValido = new Semantico();
        semanticoValido.analizar(valido.sintactico.getArbol());
        comprobar(!semanticoValido.hasErrors(),
                "Semantico: debe resolver campos, parámetros, locales y métodos declarados.");

        Resultado invalido = analizar("{ class Main { public main() { desconocido = 1; } } }");
        Semantico semanticoInvalido = new Semantico();
        semanticoInvalido.analizar(invalido.sintactico.getArbol());
        comprobar(semanticoInvalido.hasErrors(),
                "Semantico: debe reportar identificadores no declarados en cuerpos.");
        comprobar(semanticoInvalido.getErrores().get(0).TIPOERROR == compiladorErrores.TIPO_ERROR.SEMANTICO,
                "Semantico: los diagnósticos deben distinguirse de los sintácticos.");
    }

    private static void compruebaTiposSemanticos() {
        Resultado valido = analizar("{ class Main { public main() { float x = 1 + 2.5; bool ok = x > 1; } } }");
        Semantico semanticoValido = new Semantico();
        semanticoValido.analizar(valido.sintactico.getArbol());
        comprobar(!semanticoValido.hasErrors(), "Semantico: debe promover int a float y tipar comparaciones.");

        Resultado invalido = analizar("{ class Main { public main() { bool ok = 1 + 2; } } }");
        Semantico semanticoInvalido = new Semantico();
        semanticoInvalido.analizar(invalido.sintactico.getArbol());
        comprobar(semanticoInvalido.hasErrors(), "Semantico: debe rechazar una asignación de int a bool.");
    }

    private static void compruebaAsignacionCompuestaSemantica() {
        Resultado valido = analizar("{ class Main { public main() { int n = 1; n += 2; } } }");
        Semantico semanticoValido = new Semantico();
        semanticoValido.analizar(valido.sintactico.getArbol());
        comprobar(!semanticoValido.hasErrors(), "Semantico: debe aceptar '+=' numérico.");

        Resultado invalido = analizar("{ class Main { public main() { bool activo = true; activo *= 2; } } }");
        Semantico semanticoInvalido = new Semantico();
        semanticoInvalido.analizar(invalido.sintactico.getArbol());
        comprobar(semanticoInvalido.hasErrors(), "Semantico: debe rechazar '*=' sobre bool.");
    }

    private static void compruebaAnotacionDeTipos() {
        Resultado resultado = analizar("{ class Main { public main() { float x = 1 + 2.5; } } }");
        Semantico semantico = new Semantico();
        semantico.analizar(resultado.sintactico.getArbol());
        NodoArbolSintactico expresion = buscar(resultado.sintactico.getArbol(), "EXPRESION: +");
        comprobar(expresion != null && Semantico.FLOAT.equals(expresion.getTipoSemantico()),
                "Semantico: cada expresión debe conservar su tipo sintetizado en el AST.");
    }

    private static void compruebaInicializadoresDeCampo() {
        Resultado valido = analizar("{ class Main { float total = 1; public main() { } } }");
        Semantico semanticoValido = new Semantico();
        semanticoValido.analizar(valido.sintactico.getArbol());
        comprobar(!semanticoValido.hasErrors(), "Semantico: debe permitir inicializar float con int.");

        Resultado invalido = analizar("{ class Main { bool activo = 1; public main() { } } }");
        Semantico semanticoInvalido = new Semantico();
        semanticoInvalido.analizar(invalido.sintactico.getArbol());
        comprobar(semanticoInvalido.hasErrors(), "Semantico: debe rechazar un inicializador de campo incompatible.");
    }

    private static void compruebaFlujoDeRetorno() {
        Resultado completo = analizar("{ class Main { int f(bool b) { if (b) { return 1; } else { return 2; } } public main() { } } }");
        Semantico semanticoCompleto = new Semantico();
        semanticoCompleto.analizar(completo.sintactico.getArbol());
        comprobar(!semanticoCompleto.hasErrors(), "Semantico: ambas ramas de if/else deben garantizar retorno.");

        Resultado incompleto = analizar("{ class Main { int f(bool b) { if (b) { return 1; } } public main() { } } }");
        Semantico semanticoIncompleto = new Semantico();
        semanticoIncompleto.analizar(incompleto.sintactico.getArbol());
        comprobar(semanticoIncompleto.hasErrors(), "Semantico: if sin else no garantiza retorno.");
    }

    private static void compruebaConstructoresImplicitos() {
        String clases = " class Persona { public void Persona(int n) { } } "
                + "class Hija extends Persona { } class Otra { } }";
        String inicio = "{ class Main { Persona campo = new Persona(); "
                + "Persona crear() { return new Hija(); } "
                + "void recibir(Persona p) { } public main() { ";
        Resultado valido = analizar(inicio
                + "var p = new Persona(); p.Persona(1); recibir(new Hija()); "
                + "Persona base = new Hija(); } }" + clases);
        comprobar(!valido.lexico.hasErrors() && !valido.sintactico.hasErrors(),
                "Constructores: el programa válido debe respetar la gramática.");
        Semantico semantico = new Semantico();
        semantico.analizar(valido.sintactico.getArbol());
        comprobar(!semantico.hasErrors(),
                "Constructores: deben funcionar campos, inferencia, retornos, argumentos y herencia.");
        NodoArbolSintactico nuevo = buscar(valido.sintactico.getArbol(), "NEW: new");
        comprobar(nuevo != null && Semantico.clase("Persona").equals(nuevo.getTipoSemantico()),
                "Constructores: new debe anotar el tipo de la clase en el AST.");

        String[][] invalidos = {
                { "Persona p = new Persona(1);", "cero argumentos" },
                { "Persona p = new Persona(1, true);", "cero argumentos" },
                { "Persona p = new Hija(1);", "cero argumentos" },
                { "var p = new Desconocida();", "No existe la clase 'Desconocida'" },
                { "Otra p = new Persona();", "", },
                { "Persona p = new Persona(desconocido);", "cero argumentos" }
        };
        for (String[] caso : invalidos) {
            Resultado resultado = analizar(inicio + caso[0] + " } }" + clases);
            comprobar(!resultado.lexico.hasErrors() && !resultado.sintactico.hasErrors(),
                    "Constructores: el rechazo debe ser semántico: " + caso[0]);
            semantico.analizar(resultado.sintactico.getArbol());
            comprobar(semantico.getErrores().stream().anyMatch(e -> e.mensaje.contains(caso[1])),
                    "Constructores: falta el diagnóstico esperado: " + caso[0]);
            if (caso[0].contains("desconocido")) {
                comprobar(semantico.getErrores().stream().anyMatch(e -> e.mensaje.contains("desconocido")),
                        "Constructores: también deben analizarse los argumentos rechazados.");
            }
        }
    }

    private static void rechazaLexico(String nombre, String fuente) {
        Resultado resultado = analizar(fuente);
        comprobar(resultado.lexico.hasErrors(), nombre + ": se esperaba un error lexico.");
    }

    private static void rechazaSintactico(String nombre, String fuente) {
        Resultado resultado = analizar(fuente);
        comprobar(!resultado.lexico.hasErrors(), nombre + ": el caso debe llegar limpio al parser.");
        comprobar(resultado.sintactico.hasErrors(), nombre + ": se esperaba un error sintactico.");
    }

    private static void conservaUbicacionFuente() {
        Resultado resultado = analizar("{ public class Main { public main() { int x = 1; } } }");
        NodoArbolSintactico identificador = buscar(resultado.sintactico.getArbol(), "IDENTIFICADOR: x");
        comprobar(identificador != null, "AST: no se encontro el identificador declarado.");
        comprobar(identificador.getLinea() == 1 && identificador.getColumna() > 0,
                "AST: el identificador debe conservar linea y columna de origen.");
        NodoArbolSintactico bloque = buscar(resultado.sintactico.getArbol(), "BLOQUE");
        comprobar(bloque != null && bloque.getLinea() == 1 && bloque.getColumna() > 0,
                "AST: un bloque debe conservar la posicion de su llave de apertura.");
        comprobar(resultado.sintactico.getArbol().getLinea() == 1,
                "AST: el programa debe conservar la posicion inicial del archivo.");
    }

    private static void recuperaDosExpresionesFaltantes() {
        Resultado resultado = analizar("{ public class Main { public main() { int x = ; bool y = ; } } }");
        comprobar(!resultado.lexico.hasErrors(), "Recuperacion: el caso debe llegar limpio al parser.");
        comprobar(resultado.sintactico.getErrores().size() == 2,
                "Recuperacion: cada expresion faltante debe producir un unico diagnostico.");
    }

    private static void recuperaListasSinComa() {
        verificaUnErrorSintactico("{ public class Main { public main() { f(1 2); } } }",
                "Recuperacion: argumentos sin coma.");
        verificaUnErrorSintactico("{ public class Main { void f(int a string b) { } public main() { } } }",
                "Recuperacion: parametros sin coma.");
        verificaUnErrorSintactico("{ public class Main { public main() { list<int> xs = [1 2]; } } }",
                "Recuperacion: arreglo sin coma.");
    }

    private static void verificaUnErrorSintactico(String fuente, String contexto) {
        Resultado resultado = analizar(fuente);
        comprobar(!resultado.lexico.hasErrors(), contexto + " debe llegar limpio al parser.");
        comprobar(resultado.sintactico.getErrores().size() == 1,
                contexto + " debe recuperarse con un único diagnóstico.");
    }

    private static void recuperaImportsMalformados() {
        verificaUnErrorSintactico("import local extra; { public class Main { public main() { } } }",
                "Recuperacion: import con identificador extra.");
        verificaUnErrorSintactico("import local { public class Main { public main() { } } }",
                "Recuperacion: import sin punto y coma.");
    }

    private static void recuperaMiembroClaseMalformado() {
        verificaUnErrorSintactico("{ public class Main { return valor; public main() { } } }",
                "Recuperacion: miembro de clase invalido.");
    }

    private static void recuperaCabeceraCondicionalMalformada() {
        verificaUnErrorSintactico("{ public class Main { public main() { if true { int x = 1; } } } }",
                "Recuperacion: cabecera de if sin parentesis de apertura.");
    }

    private static void recuperaCabeceraWhileMalformada() {
        verificaUnErrorSintactico("{ public class Main { public main() { while true { int x = 1; } } } }",
                "Recuperacion: cabecera de while sin parentesis de apertura.");
    }

    private static void recuperaCabeceraExistMalformada() {
        verificaUnErrorSintactico("{ public class Main { public main() { Exist true { int x = 1; } } } }",
                "Recuperacion: cabecera de Exist sin parentesis de apertura.");
    }

    private static void recuperaCabeceraRepeatMalformada() {
        verificaUnErrorSintactico("{ public class Main { public main() { repeat true { int x = 1; } } } }",
                "Recuperacion: cabecera de repeat sin parentesis de apertura.");
    }

    private static void recuperaCabeceraRepeatEachMalformada() {
        verificaUnErrorSintactico("{ public class Main { public main() { repeat each int x in xs { int y = 1; } } } }",
                "Recuperacion: cabecera de repeat each sin parentesis de apertura.");
    }

    private static void recuperaLimiteDeSentencia() {
        Resultado resultado = analizar("{ public class Main { public main() { int x = 1 int y = 2; y += 1; } } }");
        comprobar(!resultado.lexico.hasErrors(), "Recuperacion: el limite de sentencia debe llegar limpio al parser.");
        comprobar(resultado.sintactico.getErrores().size() == 1,
                "Recuperacion: un ';' omitido debe producir un único diagnóstico.");
        comprobar(buscar(resultado.sintactico.getArbol(), "IDENTIFICADOR: y") != null,
                "Recuperacion: la declaracion posterior debe conservarse en el AST.");
    }

    private static void recuperaLimitesRestantes() {
        verificaUnErrorSintactico("{ public class Main { int x = 1 public main() { } } }",
                "Recuperacion: limite de campo sin punto y coma.");
        verificaUnErrorSintactico("{ public class Main { public main() { f() int y = 1; } } }",
                "Recuperacion: llamada sin punto y coma.");
    }

    private static void recuperaCabeceraMetodoMalformada() {
        verificaUnErrorSintactico("{ public class Main { void f int x { int y = 1; } public main() { } } }",
                "Recuperacion: cabecera de metodo sin parentesis de apertura.");
    }

    private static void recuperaCabeceraMainMalformada() {
        verificaUnErrorSintactico("{ public class Main { public main { int x = 1; } } }",
                "Recuperacion: cabecera de main sin parentesis de apertura.");
    }

    private static NodoArbolSintactico buscar(NodoArbolSintactico nodo, String texto) {
        if (nodo.getTexto().equals(texto)) return nodo;
        for (NodoArbolSintactico hijo : nodo.getHijos()) {
            NodoArbolSintactico encontrado = buscar(hijo, texto);
            if (encontrado != null) return encontrado;
        }
        return null;
    }

    private static Resultado analizar(String fuente) {
        Lexico lexico = new Lexico(fuente);
        lexico.tokenizar();
        AnalizadorSintactico sintactico = new AnalizadorSintactico(lexico.getTokens(), new Gramatica());
        sintactico.analizar();
        return new Resultado(lexico, sintactico);
    }

    private static void comprobar(boolean condicion, String mensaje) {
        if (!condicion) throw new AssertionError(mensaje);
    }

    private static class Resultado {
        final Lexico lexico;
        final AnalizadorSintactico sintactico;

        Resultado(Lexico lexico, AnalizadorSintactico sintactico) {
            this.lexico = lexico;
            this.sintactico = sintactico;
        }
    }
}
