/** Regresión del motor semántico; cada caso exige sintaxis válida antes de comprobar semántica. */
public class PruebasSemanticas {
    private static int casos;

    public static void main(String[] args) {
        ejecutar();
        System.out.println("OK: " + casos + " casos del motor semantico aprobados.");
    }

    static void ejecutar() {
        casos = 0;
        compruebaImportsLocales();
        aceptaCuerpo("int x; x = 1; int y = x;");
        rechazaCuerpo("int x; int y = x;", "antes de inicializarse");
        rechazaCuerpo("int x; x += 1;", "antes de inicializarse");
        rechazaCuerpo("int x = x;", "antes de inicializarse");
        rechazaCuerpo("int x = true - 1;", "operador");
        rechazaCuerpo("bool x = 1 && true;", "operador");
        rechazaCuerpo("bool x = true < false;", "operador");
        rechazaCuerpo("bool x = 1 == true;", "operador");
        rechazaCuerpo("bool x = not 1;", "unario");
        rechazaCuerpo("int x = -true;", "unario");
        aceptaCuerpo("float x = 1 + 2.5; string s = \"n=\" + 1; bool b = null == null;");
        rechazaCuerpo("int x = 1; x;", "expresión aislada");
        rechazaCuerpo("let x = 1; x = 2;", "constante");
        rechazaCuerpo("var x = null;", "inferir");
        rechazaCuerpo("var x = [];", "inferir");
        rechazaCuerpo("var x = [[]];", "inferir");
        aceptaCuerpo("list<int> xs = []; xs = [1, 2]; xs[0] = 3;");
        aceptaCuerpo("list<float> xs = [1, 2.5]; list<list<int>> ys = [[], [1]];");
        rechazaCuerpo("int x = [];", "asignar");
        rechazaCuerpo("list<int> xs = [true];", "Elemento incompatible");
        rechazaCuerpo("list<int> xs = [1]; list<float> ys = xs;", "asignar");
        rechazaCuerpo("list<int> xs = [1]; int y = xs[true];", "índice");
        rechazaCuerpo("int x = 1; int y = x[0];", "requiere list");
        aceptaCuerpo("list<int> xs = [1]; repeat each (float x in xs) { float y = x; }");
        rechazaCuerpo("repeat each (int x in 1) { }", "repeat each");
        rechazaCuerpo("repeat each (bool x in [1]) { }", "Elemento incompatible");
        rechazaCuerpo("repeat each (int x in [1]) { } int y = x;", "No existe");
        aceptaCuerpo("int x = 1; while (true) { x += 1; } repeat (false) { x += 1; } Exist([1]) { x += 1; }");
        rechazaCuerpo("while (1) { }", "condición");
        rechazaCuerpo("repeat (1) { }", "condición");
        rechazaCuerpo("Exist(1) { }", "Exist requiere");
        aceptaCuerpo("int x; if (true) { x = 1; } else { x = 2; } int y = x;");
        rechazaCuerpo("int x; if (true) { x = 1; } int y = x;", "antes de inicializarse");
        rechazaCuerpo("int x; while (true) { x = 1; } int y = x;", "antes de inicializarse");
        aceptaCuerpo("int x; if (true) { return; } else { x = 1; } int y = x;");
        aceptaCuerpo("int x; if (true) { x = 1; } else { return; } int y = x;");
        rechazaCuerpo("return; int x = 1;", "inalcanzable");
        rechazaCuerpo("int x = 1; if (true) { int x = 2; }", "ya fue declarada");
        aceptaCuerpo("if (true) { int x = 1; } else { int x = 2; }");
        rechazaCuerpo("int x = 1; repeat each (int x in [1]) { }", "iterador");

        acepta("{ class A { public int x; public int f() { return x; } } class Main extends A { public main() { x = 1; int y = f(); } } }");
        rechaza("{ class A { int x; } class Main extends A { public main() { x = 1; } } }", "privado");
        rechaza("{ class A { int f() { return 1; } } class Main extends A { public main() { int x = f(); } } }", "firma accesible");
        rechaza("{ class A { int x; } class Main extends A { public main() { Main m = new Main(); int x = m.x; } } }", "privado");
        acepta("{ class A { int x; int leer(A a) { return a.x; } } class Main { public main() { } } }");
        rechaza("{ class A extends B { } class B extends A { } class Main extends A { public main() { } } }", "ciclo");
        rechaza("{ class Main extends Main { public main() { } } }", "sí misma");
        rechaza("{ class Main extends Falta { public main() { } } }", "clase base");
        rechaza("{ class Main { public main() { } } class Main { int x; } }", "clase 'Main'");
        rechaza("{ class Main { int x; int x; public main() { } } }", "campo");
        rechaza("{ class Main { void f(int x) { } int f(int y) { return 1; } public main() { } } }", "firma igual");
        rechaza("{ class Main { void f(int x, int x) { } public main() { } } }", "parámetro");
        rechaza("{ class Main { Falta x; public main() { } } }", "tipo 'Falta'");
        rechaza("{ class Main { int f() { } public main() { } } }", "sin retornar");
        rechaza("{ class Main { int f() { return; } public main() { } } }", "retorno");
        rechaza("{ class Main { void f() { return 1; } public main() { } } }", "retorno");
        rechaza("{ class Main { void f() { } public main() { return f(); } } }", "retorno");
        rechaza("{ class Main { void f() { } public main() { var x = f(); } } }", "inferir");
        rechaza("{ class Main { void f() { } public main() { list<int> xs = [f()]; } } }", "Elemento incompatible");
        acepta("{ class Main { int f(bool b) { if (b) { return 1; } else if (b) { return 2; } else { return 3; } } public main() { } } }");
        acepta("{ class A { } class B extends A { } class Main { public main() { A a = new B(); B b = new B(); bool x = a == b; list<A> xs = [new B()]; var ys = [new B(), new A()]; } } }");
        rechaza("{ class A { } class B { } class Main { public main() { A a = new A(); B b = new B(); bool x = a == b; } } }", "operador");
        acepta("{ class A { public A f() { return new A(); } } class B extends A { public B f() { return new B(); } } class Main { public main() { } } }");
        rechaza("{ class A { public int f() { return 1; } } class B extends A { private int f() { return 1; } } class Main { public main() { } } }", "visibilidad");
        rechaza("{ class A { public float f() { return 1; } } class B extends A { public int f() { return 1; } } class Main { public main() { } } }", "retorno");
        acepta("{ class A { private int f() { return 1; } } class B extends A { private bool f() { return true; } } class Main { public main() { } } }");
        acepta("{ class Main { bool f(float x) { return true; } int f(int x) { return 1; } public main() { int x = f(1); } } }");
        acepta("{ class A { } class B extends A { } class Main { bool f(A x) { return true; } int f(B x) { return 1; } public main() { int x = f(new B()); int y = f(null); } } }");
        rechaza("{ class A { } class B { } class Main { void f(A x) { } void f(B x) { } public main() { f(null); } } }", "ambigua");
        rechaza("{ class Main { void f(int x, float y) { } void f(float x, int y) { } public main() { f(1, 1); } } }", "ambigua");
        acepta("{ class A { public int f() { return 1; } } class Main extends A { public int f() { return 2; } public main() { int x = f(); } } }");
        acepta("{ class Main { void f(list<float> xs) { } public main() { f([]); f([1]); } } }");
        rechaza("{ class Main { void f(list<int> xs) { } void f(list<bool> xs) { } public main() { f([]); } } }", "ambigua");
        rechaza("{ class Main { int f() { return 1; } public main() { f() = 2; } } }", "no es modificable");
        rechaza("{ class Main { int f() { return 1; } public main() { f()(); } } }", "invocable");
        rechaza("{ class Main { public main() { int x = 1; x.f(); } } }", "objeto de clase");
        acepta("{ class Main { list<int> xs = []; list<int> f() { return []; } public main() { list<int> ys = f(); } } }");
    }

    private static void compruebaImportsLocales() {
        java.nio.file.Path carpeta = null;
        try {
            carpeta = java.nio.file.Files.createTempDirectory("semantico-imports-");
            java.nio.file.Path principal = carpeta.resolve("Main.txt");
            java.nio.file.Path persona = carpeta.resolve("Persona.txt");
            java.nio.file.Files.writeString(principal, "import Persona; { class Main { public main() { Persona p = new Persona(); int x = p.edad(); } } }");
            java.nio.file.Files.writeString(persona, "import Main; { class Persona { public int edad() { return 1; } public main() { } } }");
            java.util.List<Main.ResultadoAnalisis> resultados = Main.analizarProyecto(principal, true);
            if (resultados.size() != 2) throw new AssertionError("Imports cíclicos: cada unidad debe cargarse una vez.");
            for (Main.ResultadoAnalisis resultado : resultados) {
                if (resultado.semantico == null || resultado.semantico.hasErrors() || resultado.abortadoPorDependencia)
                    throw new AssertionError("Imports: no se resolvieron conjuntamente las clases.");
            }
            casos++;
            java.nio.file.Files.writeString(persona, "{ class Persona { public int edad() { return true; } public main() { } } }");
            resultados = Main.analizarProyecto(principal, true);
            for (Main.ResultadoAnalisis resultado : resultados) {
                boolean dependencia = resultado.archivo.equals(persona);
                if (resultado.semantico == null || resultado.semantico.hasErrors() != dependencia)
                    throw new AssertionError("El diagnóstico semántico debe pertenecer a su archivo.");
                if (resultado.resultadoSemantico.esExitoso() || resultado.resultadoSemantico.getAstValidado() != null)
                    throw new AssertionError("Una dependencia inválida debe impedir salida validada del proyecto.");
                if (!dependencia && !resultado.abortadoPorDependencia)
                    throw new AssertionError("Debe propagarse el fallo semántico de la dependencia.");
            }
            casos++;
            java.nio.file.Files.writeString(persona, "{ class Persona { public int edad() { return 1; } public main() { } } }");
            resultados = Main.analizarProyecto(principal, false);
            if (resultados.size() != 1 || resultados.get(0).semantico == null || !resultados.get(0).semantico.hasErrors())
                throw new AssertionError("Sin imports locales no deben inventarse clases externas.");
            casos++;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            if (carpeta != null) {
                try {
                    java.nio.file.Files.deleteIfExists(carpeta.resolve("Main.txt"));
                    java.nio.file.Files.deleteIfExists(carpeta.resolve("Persona.txt"));
                    java.nio.file.Files.deleteIfExists(carpeta);
                } catch (java.io.IOException e) { throw new AssertionError(e); }
            }
        }
    }

    private static void aceptaCuerpo(String cuerpo) { acepta(programa(cuerpo)); }
    private static void rechazaCuerpo(String cuerpo, String mensaje) { rechaza(programa(cuerpo), mensaje); }
    private static String programa(String cuerpo) { return "{ class Main { public main() { " + cuerpo + " } } }"; }
    private static void acepta(String fuente) { comprobar(fuente, null); }
    private static void rechaza(String fuente, String mensaje) { comprobar(fuente, mensaje); }

    private static void comprobar(String fuente, String mensaje) {
        casos++;
        Lexico lexico = new Lexico(fuente);
        lexico.tokenizar();
        AnalizadorSintactico parser = new AnalizadorSintactico(lexico.getTokens(), new Gramatica());
        parser.analizar();
        if (lexico.hasErrors() || parser.hasErrors()) throw new AssertionError("Caso mal formado: " + fuente + " " + parser.getErrores());
        Semantico semantico = new Semantico();
        semantico.analizar(parser.getArbol());
        for (compiladorErrores error : semantico.getErrores()) {
            if (error.linea < 1 || error.columna < 1) throw new AssertionError("Diagnóstico sin ubicación: " + error);
        }
        String errores = semantico.getErrores().toString();
        if (mensaje == null ? semantico.hasErrors() : !errores.contains(mensaje)) {
            throw new AssertionError("Caso " + casos + ": " + fuente + "\nEsperado: " + mensaje + "\nObtenido: " + errores);
        }
        // El motor debe poder reutilizarse, también sobre el mismo AST.
        semantico.analizar(parser.getArbol());
        if (!errores.equals(semantico.getErrores().toString())) throw new AssertionError("Análisis no reproducible: " + fuente);
    }
}
