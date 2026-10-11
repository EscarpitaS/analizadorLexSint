import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Oráculos de ejecución independientes de la forma textual de la IR. */
public final class PruebasGeneracionIr {
    private static int casos;
    private static Path fuente;
    public static void main(String[] args) { ejecutar(); }
    static void ejecutar() {
        casos = 0;
        try {
            fuente = Files.createTempFile("h2-fuente-", ".txt");
            ejecucion(); semanticaConsola(); barreras(); errores(); modeloRuntime(); consola(); cli();
            System.out.println("OK: " + casos + " comprobaciones de generación, ejecución IR y consola H2.");
        } catch (IOException e) { throw new AssertionError(e); }
        finally { try { if (fuente != null) Files.deleteIfExists(fuente); } catch (IOException e) { throw new AssertionError(e); } }
    }
    private static void comprobar(boolean b, String detalle) { casos++; if (!b) throw new AssertionError(detalle); }
    private static String programa(String cuerpo) { return "import runtime.Consola; { public class Main { public main() { Consola c = new Consola(); " + cuerpo + " } } }"; }
    private static List<Main.ResultadoAnalisis> analizar(String texto, boolean locales) { try { return Main.analizarProyecto(fuente, texto, locales); } catch (IOException e) { throw new AssertionError(e); } }
    private static ModuloIr generar(String texto) {
        ResultadoGeneracionIr r = new GeneradorIr().generar(analizar(texto, false), null);
        comprobar(r.esExitoso(), "Generación falló: " + r.errores);
        comprobar(new VerificadorIr().verificar(r.modulo).esExitoso(), "IR generada no verificable");
        return r.modulo;
    }
    private static String ejecutar(String texto, byte[] input) {
        ModuloIr m = generar(texto); ByteArrayOutputStream out = new ByteArrayOutputStream();
        new InterpreteIr().ejecutar(m, new ConsolaReferencia(new ByteArrayInputStream(input), out), 2000000);
        ByteArrayOutputStream plano = new ByteArrayOutputStream();
        ModuloAtomicoIr atomico = new LinealizadorIr().transformar(m);
        new InterpreteAtomicoIr().ejecutar(atomico, new ConsolaReferencia(new ByteArrayInputStream(input), plano), 4000000);
        comprobar(Arrays.equals(out.toByteArray(), plano.toByteArray()), "IR tipada/atómica conservan bytes de salida");
        return out.toString(StandardCharsets.UTF_8);
    }
    private static void salida(String cuerpo, String input, String esperado) {
        String recibido = ejecutar(programa(cuerpo), input.getBytes(StandardCharsets.UTF_8));
        comprobar(recibido.equals(esperado), "Salida esperada " + esperado + ", recibida " + recibido);
    }
    private static void ejecucion() {
        salida("int x = 7; x += 3; x *= 2; x /= 3; x -= 1; c.imprimirLinea(x); c.imprimirLinea(-7 / 3);", "", "5\n-2\n");
        salida("int max = 9223372036854775807; c.imprimirLinea(max + 1); int min = -9223372036854775808; c.imprimirLinea(min / -1); c.imprimirLinea(-min);", "", "-9223372036854775808\n-9223372036854775808\n-9223372036854775808\n");
        salida("c.imprimirLinea(-0009223372036854775808);", "", "-9223372036854775808\n");
        salida("float x = 1; x += 2; x *= 1.5; c.imprimirLinea(x / 2); c.imprimirLinea(1 < 1.5);", "", "2.25\ntrue\n");
        salida("c.imprimirLinea(9007199254740993 > 9007199254740992); c.imprimirLinea(9223372036854775807 != 9223372036854775806);", "", "true\ntrue\n");
        salida("float x = c.leerFloat(); float inf = x * x; float nan = inf - inf; c.imprimirLinea(inf); c.imprimirLinea(nan); c.imprimirLinea(nan == nan); c.imprimirLinea(nan < 1.0);", "1e308\n", "Infinity\nNaN\nfalse\nfalse\n");
        salida("string s = null; c.imprimirLinea(s == null); c.imprimirLinea(s == \"null\"); c.imprimirLinea(s != null); c.imprimirLinea(null == s);", "", "true\nfalse\nfalse\ntrue\n");
        salida("string s = null; c.imprimirLinea(s); c.imprimirLinea(s + 4); c.imprimirLinea(\"a\" == \"a\"); c.imprimirLinea(\"a\" != \"b\");", "", "null\nnull4\ntrue\ntrue\n");
        salida("string a = \"ab\" + \"cd\"; string b = \"abc\" + \"d\"; c.imprimirLinea(a == b); c.imprimirLinea(a != b);", "", "true\nfalse\n");
        salida("c.imprimir(\"%d\\n\"); c.imprimirLinea(\"á😀\u0000\"); c.imprimirLinea();", "", "%d\\ná😀\u0000\n\n");
        salida("int x; if (c.leerBool()) { x = 1; } else { x = 2; } c.imprimirLinea(x);", "true\n", "1\n");
        salida("int x; if (c.leerBool()) { x = 1; } else { x = 2; } c.imprimirLinea(x);", "false\n", "2\n");
        salida("int n = c.leerInt(); if (n < 0) { c.imprimirLinea(\"neg\"); } else if (n == 0) { c.imprimirLinea(\"cero\"); } else { c.imprimirLinea(\"pos\"); }", "0\n", "cero\n");
        salida("int n = 3; while (n > 0) { c.imprimir(n); n -= 1; } repeat (false) { c.imprimir(\"X\"); } repeat (n < 2) { n += 1; } c.imprimirLinea(n);", "", "3212\n");
        salida("bool a = false && c.leerBool(); bool b = true || c.leerBool(); c.imprimirLinea(a); c.imprimirLinea(b); c.imprimirLinea(c.leerInt());", "42\n", "false\ntrue\n42\n");
        salida("c.imprimirLinea(true && (false || c.leerBool())); c.imprimirLinea(c.leerInt());", "true\n7\n", "true\n7\n");
        salida("c.imprimirLinea(not (false || false) && (1 <= 2) && (3 >= 3) && (4 > 2));", "", "true\n");
        salida("int n = 2; bool b = false; while (n > 0 && c.leerBool()) { n -= 1; b = true; } c.imprimirLinea(n); c.imprimirLinea(b);", "true\nfalse\n", "1\ntrue\n");
        salida("c.imprimirLinea(c.leerInt()); c.imprimirLinea(c.leerFloat()); c.imprimirLinea(c.leerBool()); c.imprimirLinea(c.leerLinea()); c.imprimirLinea(c.leerLinea());", " 21\r\n-0.0\ntrue\nJosé😀", "21\n-0.0\ntrue\nJosé😀\nnull\n");
        salida("Consola d = new Consola(); c.imprimirLinea(c == d); Consola a = c; c.imprimirLinea(a == c); c.imprimirLinea(d);", "", "false\ntrue\n<Consola>\n");
        String metodos = "import runtime.Consola; { public class Main { "
                + "int fact(int n) { if (n <= 1) { return 1; } else { return n * fact(n - 1); } } "
                + "public float subir(float n) { n += 1; return n; } int subir(int n) { n += 2; return n; } "
                + "void decir(Consola c, int n) { c.imprimirLinea(n); return; } "
                + "public main() { Consola c = new Consola(); decir(c, fact(6)); c.imprimirLinea(subir(2)); c.imprimirLinea(subir(2.0)); } } }";
        comprobar(ejecutar(metodos, new byte[0]).equals("720\n4\n3.0\n"), "Sobrecargas, parámetros, retorno, recursión, público/privado");
        String nullable = "import runtime.Consola; { public class Main { string devolver(string s) { return s; } string vacia() { return null; } public main() { Consola c = new Consola(); string s = null; c.imprimirLinea(devolver(s) == null); c.imprimirLinea(vacia() == null); } } }";
        comprobar(ejecutar(nullable, new byte[0]).equals("true\ntrue\n"), "Null string preservado en parámetros y retornos");
        String orden = "import runtime.Consola; { public class Main { Consola receptor(Consola c) { c.imprimir(\"R\"); return c; } int argumento(Consola c) { c.imprimir(\"A\"); return 7; } public main() { Consola c = new Consola(); receptor(c).imprimirLinea(argumento(c)); int x = 1; x += argumento(c); c.imprimirLinea(x); } } }";
        comprobar(ejecutar(orden, new byte[0]).equals("RA7\nA8\n"), "Orden receptor/argumento y evaluación única");
        String profunda = "import runtime.Consola; { public class Main { int bajar(int n) { if (n == 0) { return 0; } return bajar(n - 1); } public main() { Consola c = new Consola(); c.imprimirLinea(bajar(12000)); } } }";
        comprobar(ejecutar(profunda, new byte[0]).equals("0\n"), "Recursión sin desbordar pila Java");
        String hermanos = programa("if (true) { int x = 2; c.imprimir(x); } if (true) { int x = 3; c.imprimir(x); }");
        ModuloIr m = generar(hermanos);
        comprobar(m.funciones.get(0).locales.stream().filter(s -> s.nombre.equals("x")).map(s -> s.id).distinct().count() == 2, "Identidad de locales homónimas");
        comprobar(ejecutar(hermanos, new byte[0]).equals("23"), "Almacenamiento independiente");
        ModuloIr estable = generar(metodos);
        comprobar(new ImpresorIr().imprimir(estable).equals(new ImpresorIr().imprimir(generar(metodos))), "Generación determinista");
        comprobar(estable.funciones.stream().flatMap(f -> f.bloques.stream()).flatMap(b -> b.instrucciones.stream()).allMatch(i -> i.origen.generado || i.origen.archivo.equals(fuente.toString())), "Ubicaciones fuente");
    }
    private static void semantica(String texto, boolean correcto, boolean locales) {
        List<Main.ResultadoAnalisis> us = analizar(texto, locales);
        boolean exito = us.stream().allMatch(u -> u.resultadoSemantico != null && u.resultadoSemantico.esExitoso());
        comprobar(exito == correcto, "Semántica inesperada: " + texto + " " + us.get(0).diagnosticos());
    }
    private static void semanticaConsola() {
        semantica(programa("c.imprimirLinea(); c.imprimir(null); c.imprimir([1, 2]);"), true, true);
        semantica(programa("c.imprimirLinea();"), true, false);
        semantica("import runtime.Consola; " + programa(""), true, true);
        for (String cuerpo : Arrays.asList("c.imprimir();", "c.imprimir(1, 2);", "c.imprimirLinea(1, 2);", "c.leerInt(1);", "c.leerFloat(1);", "c.leerBool(false);", "c.leerLinea(\"a\");", "c.otra();", "c.imprimir(c.imprimir(1));", "c.imprimir([]);", "c.imprimir([null]);", "Consola d = new Consola(1);")) semantica(programa(cuerpo), false, false);
        semantica("{ public class Main { public main() { Consola c = new Consola(); } } }", false, false);
        semantica("import runtime.Consola; { public class Consola {} public class Main { public main() {} } }", false, false);
        semantica("import runtime.Consola; { public class Hija extends Consola {} public class Main { public main() {} } }", false, false);
        String propia = "{ public class Consola { public int leerInt() { return 7; } } public class Main { public main() { Consola c = new Consola(); int n = c.leerInt(); } } }";
        semantica(propia, true, false);
    }
    private static void rechazado(String texto, String codigo) {
        ResultadoGeneracionIr r = new GeneradorIr().generar(analizar(texto, false), null);
        comprobar(!r.esExitoso() && r.modulo == null && r.errores.toString().contains(codigo), "Falta barrera " + codigo + ": " + r.errores + " FUENTE: " + texto);
    }
    private static void barreras() throws IOException {
        rechazado(programa("int x = ;"), "Proyecto incompleto");
        rechazado(programa("c.imprimir(noExiste);"), "Proyecto incompleto");
        rechazado(programa("int x = -9223372036854775809;"), "LITERAL_FUERA_DE_RANGO");
        rechazado(programa("int x = 9223372036854775808;"), "LITERAL_FUERA_DE_RANGO");
        rechazado(programa("float x = " + "9".repeat(320) + ".0;"), "LITERAL_FUERA_DE_RANGO");
        for (String cuerpo : Arrays.asList("list<int> xs = [];", "repeat each (int x in [1]) {}", "Exist([1]) {}", "Main m = new Main();", "if (false) { list<int> xs = []; }")) generar(programa(cuerpo));
        generar("{ public class Main { int campo; public main() {} } }");
        generar("{ public class A {} public class Main extends A { public main() {} } }");
        String varios = "{ public class A { public main() {} } public class B { public main() {} } }";
        rechazado(varios, "Hay varios main");
        ResultadoGeneracionIr elegido = new GeneradorIr().generar(analizar(varios, false), "B");
        comprobar(elegido.esExitoso() && elegido.modulo.entrada.propietaria.equals("B"), "Selección explícita de entrada");
        Path dir = Files.createTempDirectory("h2-imports-");
        try {
            Path principal = dir.resolve("Principal.txt"), biblioteca = dir.resolve("Aux.txt");
            Files.writeString(biblioteca, "import runtime.Consola; { public class Aux { public main() {} } }");
            Files.writeString(principal, "import Aux; { public class Main { public main() { Consola c = new Consola(); c.imprimir(1); } } }");
            List<Main.ResultadoAnalisis> us = Main.analizarProyecto(principal);
            ResultadoGeneracionIr r = new GeneradorIr().generar(us, "Main");
            comprobar(us.size() == 2 && r.esExitoso(), "Consola visible entre unidades e import integrado sin unidad extra: " + r.errores);
            Files.writeString(biblioteca, "{ public class Aux { public main() { int x = ; } } }");
            r = new GeneradorIr().generar(Main.analizarProyecto(principal), "Main");
            comprobar(r.modulo == null, "Unidad importada inválida bloquea IR");
            Files.delete(biblioteca);
            r = new GeneradorIr().generar(Main.analizarProyecto(principal), "Main");
            comprobar(r.modulo == null, "Import faltante bloquea IR");
        } finally { try (java.util.stream.Stream<Path> ps = Files.walk(dir)) { for (Path p : (Iterable<Path>) ps.sorted(Comparator.reverseOrder())::iterator) Files.delete(p); } }
    }
    private static void error(String cuerpo, String input, String codigo) {
        ModuloIr m = generar(programa(cuerpo)); ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { new InterpreteIr().ejecutar(m, new ConsolaReferencia(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out), 100000); comprobar(false, "Falta " + codigo); }
        catch (ErrorEjecucionIr e) {
            comprobar(e.codigo.equals(codigo) && e.origen.archivo.equals(fuente.toString()) && e.origen.linea == 1 && e.origen.columna > 0, "Error/origen: " + e);
            ByteArrayOutputStream plano = new ByteArrayOutputStream();
            try { new InterpreteAtomicoIr().ejecutar(new LinealizadorIr().transformar(m), new ConsolaReferencia(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), plano), 100000); comprobar(false, "Falta error atómico " + codigo); }
            catch (ErrorEjecucionIr atomico) { comprobar(atomico.codigo.equals(e.codigo) && atomico.origen.toString().equals(e.origen.toString()) && Arrays.equals(out.toByteArray(), plano.toByteArray()), "Error, origen y salida parcial preservados"); }
        }
    }
    private static void errores() {
        error("c.imprimirLinea(1 / 0);", "", "DIVISION_POR_CERO");
        error("c.imprimirLinea(1.0 / -0.0);", "", "DIVISION_POR_CERO");
        error("Consola n = null; n.imprimirLinea(c.leerInt());", "abc\n", "ENTRADA_INVALIDA");
        error("Consola n = null; n.imprimirLinea(c.leerInt());", "7\n", "REFERENCIA_NULA");
        error("c.leerInt();", "", "FIN_DE_ENTRADA");
        error("c.leerInt();", "9223372036854775808\n", "ENTRADA_FUERA_DE_RANGO");
        ModuloIr bucle = generar(programa("while (true) {}"));
        try { new InterpreteIr().ejecutar(bucle, new ConsolaReferencia(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()), 100); comprobar(false, "Falta límite harness"); }
        catch (InterpreteIr.LimitePruebaIr e) { comprobar(true, "Límite separado de error del lenguaje"); }
    }
    private static void modeloRuntime() {
        OrigenIr o = new OrigenIr("runtime.txt", 1, 1);
        FirmaIr f = new FirmaIr("Main", "main", TipoIr.VOID, FirmaIr.Visibilidad.PUBLICA, Collections.emptyList());
        ValorIr rec = ValorIr.temporal("r", TipoIr.objeto("Consola"));
        for (RuntimeConsolaId id : RuntimeConsolaId.values()) {
            ValorIr resultado = id.imprime() ? null : ValorIr.temporal("v", id.retorno);
            InstruccionIr ins = id.imprime() ? InstruccionIr.runtime(id, resultado, o, rec, ValorIr.cadena("")) : InstruccionIr.runtime(id, resultado, o, rec);
            ModuloIr m = runtimeModulo(f, rec, ins, true);
            comprobar(new VerificadorIr().verificar(m).esExitoso(), "Catálogo runtime " + id);
            comprobar(!new VerificadorIr().verificar(runtimeModulo(f, rec, ins, false)).esExitoso(), "Consola user no habilita intrínseco");
        }
        FirmaIr fingida = new FirmaIr("Consola", "leerInt", TipoIr.INT, FirmaIr.Visibilidad.PUBLICA, Collections.emptyList());
        FuncionIr cuerpo = new FuncionIr(fingida, ValorIr.parametro("this", TipoIr.objeto("Consola")), Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new BloqueIr("b", Collections.emptyList(), TerminadorIr.retorno(ValorIr.entero(1), o))));
        ModuloIr falso = new ModuloIr("Falso", Collections.singletonList(new ClaseIr("Consola", null, Collections.emptyList(), true)), Collections.singletonList(cuerpo), null);
        comprobar(!new VerificadorIr().verificar(falso).esExitoso(), "Intrínseco no admite un cuerpo fingido");
        InstruccionIr sinId = InstruccionIr.operacion(InstruccionIr.Operacion.LLAMAR_RUNTIME, null, o, rec);
        comprobar(!new VerificadorIr().verificar(runtimeModulo(f, rec, sinId, true)).esExitoso(), "Falta ID runtime");
        InstruccionIr malo = InstruccionIr.runtime(RuntimeConsolaId.CONSOLA_IMPRIMIR, null, o, rec, ValorIr.entero(1));
        comprobar(!new VerificadorIr().verificar(runtimeModulo(f, rec, malo, true)).esExitoso(), "Imprimir requiere conversión string explícita");
        malo = InstruccionIr.runtime(RuntimeConsolaId.CONSOLA_LEER_INT, ValorIr.temporal("n", TipoIr.FLOAT), o, rec);
        comprobar(!new VerificadorIr().verificar(runtimeModulo(f, rec, malo, true)).esExitoso(), "Resultado lectura mal tipado");
    }
    private static ModuloIr runtimeModulo(FirmaIr f, ValorIr rec, InstruccionIr ins, boolean integrada) {
        OrigenIr o = ins.origen;
        FuncionIr fun = new FuncionIr(f, ValorIr.parametro("this", TipoIr.objeto("Main")), Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new BloqueIr("b", Arrays.asList(InstruccionIr.operacion(InstruccionIr.Operacion.CREAR_OBJETO, rec, o), ins), TerminadorIr.retorno(null, o))));
        return new ModuloIr("Prueba", Arrays.asList(new ClaseIr("Main", null, Collections.emptyList()), new ClaseIr("Consola", null, Collections.emptyList(), integrada)), Collections.singletonList(fun), f);
    }
    private static void consola() {
        comprobar(ConsolaReferencia.texto(1e23).equals("9.999999999999999E22"), "Java 11 difiere del formateador reciente");
        comprobar(ConsolaReferencia.texto(Double.MIN_VALUE).equals("4.9E-324"), "Subnormal mínimo Java 11");
        comprobar(ConsolaReferencia.texto(-0.0).equals("-0.0"), "Cero negativo");
        comprobar(ConsolaReferencia.texto(Double.NaN).equals("NaN") && ConsolaReferencia.texto(Double.POSITIVE_INFINITY).equals("Infinity"), "No finitos de operaciones");
        for (double x : new double[]{0.001, 0.0001, 9999999.0, 10000000.0}) {
            String esperado = x == 0.001 ? "0.001" : x == 0.0001 ? "1.0E-4" : x == 9999999.0 ? "9999999.0" : "1.0E7";
            comprobar(ConsolaReferencia.texto(x).equals(esperado), "Umbral Java 11 " + x);
        }
        int[] flush = {0}; ByteArrayOutputStream out = new ByteArrayOutputStream() { @Override public void flush() { flush[0]++; } };
        ConsolaReferencia c = new ConsolaReferencia(new ByteArrayInputStream(new byte[0]), out);
        c.llamar(RuntimeConsolaId.CONSOLA_IMPRIMIR, "a", OrigenIr.GENERADO); c.llamar(RuntimeConsolaId.CONSOLA_IMPRIMIR_LINEA, "", OrigenIr.GENERADO);
        comprobar(flush[0] == 2, "Vaciado después de cada impresión");
    }
    private static void cli() throws IOException {
        Files.writeString(fuente, programa("c.imprimirLinea(c.leerInt() + 1);"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        int codigo = CompilarIr.ejecutar(new String[]{fuente.toString(), "--ejecutar"}, new ByteArrayInputStream("4\n".getBytes(StandardCharsets.UTF_8)), stdout, new PrintStream(stderr));
        comprobar(codigo == 0 && stdout.toString(StandardCharsets.UTF_8).equals("5\n") && stderr.size() == 0, "CLI sin mezclar IR con stdout del programa");
        String original = Files.readString(fuente);
        codigo = CompilarIr.ejecutar(new String[]{fuente.toString(), "--ir", fuente.toString()}, new ByteArrayInputStream(new byte[0]), stdout, new PrintStream(stderr));
        comprobar(codigo == 1 && Files.readString(fuente).equals(original), "Exportación no destruye fuente");
    }
}
