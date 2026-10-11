import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Oráculos H3 independientes y comparación de IR tipada/atómica. */
public final class PruebasH3 {
    private static int casos;
    private static Path fuente;
    public static void main(String[] args) { ejecutar(); }
    static void ejecutar() {
        casos = 0;
        try {
            fuente = Files.createTempFile("h3-", ".txt");
            listas(); objetos(); orden(); errores(); imports(); metadatos();
            System.out.println("OK: " + casos + " comprobaciones H3 de objetos, listas, inicialización y despacho.");
        } catch (IOException e) { throw new AssertionError(e); }
        finally { try { if (fuente != null) Files.deleteIfExists(fuente); } catch (IOException e) { throw new AssertionError(e); } }
    }
    private static void comprobar(boolean v, String m) { casos++; if (!v) throw new AssertionError(m); }
    private static String programa(String cuerpo) { return proyecto("", cuerpo); }
    private static String proyecto(String miembros, String cuerpo) {
        return "import runtime.Consola; { public class Main { " + miembros + " public main() { Consola c = new Consola(); " + cuerpo + " } } }";
    }
    private static ModuloIr generar(String s) throws IOException {
        ResultadoGeneracionIr r = new GeneradorIr().generar(Main.analizarProyecto(fuente, s, false), null);
        comprobar(r.esExitoso(), "Generación H3: " + r.errores + "\n" + s);
        comprobar(new VerificadorIr().verificar(r.modulo).esExitoso(), "IR H3 verificable");
        return r.modulo;
    }
    private static final class Resultado {
        byte[] salida;
        ErrorEjecucionIr error;
    }
    private static Resultado correr(ModuloIr m, boolean atomica, long memoria) {
        Resultado r = new Resultado(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ConsolaReferencia consola = new ConsolaReferencia(new ByteArrayInputStream(new byte[0]), out);
            if (atomica) new InterpreteAtomicoIr(memoria).ejecutar(new LinealizadorIr().transformar(m), consola, 2000000);
            else new InterpreteIr(memoria).ejecutar(m, consola, 1000000);
        } catch (ErrorEjecucionIr e) { r.error = e; }
        r.salida = out.toByteArray(); return r;
    }
    private static void caso(String s, String esperado, String error, long memoria) throws IOException {
        ModuloIr m = generar(s); Resultado t = correr(m, false, memoria), a = correr(m, true, memoria);
        comprobar(Arrays.equals(t.salida, a.salida), "Bytes tipada/atómica: " + s);
        comprobar(new String(t.salida, StandardCharsets.UTF_8).equals(esperado), "Salida esperada " + esperado + ", recibida " + new String(t.salida, StandardCharsets.UTF_8) + "\n" + s);
        comprobar(Objects.equals(t.error == null ? null : t.error.codigo, error) && Objects.equals(a.error == null ? null : a.error.codigo, error), "Error esperado " + error);
        if (error != null) comprobar(t.error.diagnostico().equals(a.error.diagnostico()) && t.error.origen.archivo.equals(fuente.toString()), "Origen/error iguales");
        comprobar(new ImpresorAtomicoIr().imprimir(new LinealizadorIr().transformar(m)).equals(new ImpresorAtomicoIr().imprimir(new LinealizadorIr().transformar(m))), "IR determinista");
    }
    private static void salida(String s, String esperado) throws IOException { caso(s, esperado, null, Long.MAX_VALUE); }
    private static void listas() throws IOException {
        salida(programa("list<int> xs = [1, 2, 3]; list<int> alias = xs; alias[1] += 5; c.imprimirLinea(xs); c.imprimirLinea(xs == alias); c.imprimirLinea(xs == [1, 7, 3]);"), "[1, 7, 3]\ntrue\nfalse\n");
        salida(programa("list<float> xs = [1, 2.5]; xs[0] *= 2; c.imprimirLinea(xs); list<string> ss = [null, \"x\"]; ss[0] += 4; c.imprimirLinea(ss);"), "[2.0, 2.5]\n[null4, x]\n");
        salida(programa("list<list<int>> xs = [[1, 2], [], [3]]; list<int> alias = xs[0]; alias[1] = 9; xs[2] = alias; c.imprimirLinea(xs); c.imprimirLinea(xs[0] == xs[2]);"), "[[1, 9], [], [1, 9]]\ntrue\n");
        salida(programa("list<int> xs = [1, 2, 3]; int suma = 0; repeat each (int x in xs) { c.imprimir(x); xs[2] = 8; x = 99; suma += 1; } c.imprimirLinea(xs); c.imprimirLinea(suma);"), "128[1, 2, 8]\n3\n");
        salida(programa("list<int> xs = [1, 2]; repeat each (float x in xs) { x += 0.5; c.imprimirLinea(x); } c.imprimirLinea(xs);"), "1.5\n2.5\n[1, 2]\n");
        salida(programa("list<string> xs = [null, \"x\"]; repeat each (string x in xs) { c.imprimirLinea(x + \"!\"); } c.imprimirLinea(xs);"), "null!\nx!\n[null, x]\n");
        salida(programa("list<int> xs = [1, 2]; repeat each (int x in xs) { xs = [9]; c.imprimir(x); } c.imprimirLinea(xs);"), "12[9]\n");
        salida(programa("list<list<int>> xs = [[1], [2, 3]]; repeat each (list<int> fila in xs) { repeat each (int x in fila) { c.imprimir(x); } }"), "123");
        salida(programa("list<int> xs = []; Exist(xs) { c.imprimir(\"X\"); } xs = [1, 2, 3]; Exist(xs) { c.imprimir(\"Y\"); } repeat each (int x in []) { c.imprimir(x); }"), "Y");
        salida(programa("Exist([]) { c.imprimir(\"X\"); } Exist([null]) { c.imprimir(\"Y\"); } c.imprimirLinea(\"lista=\" + [null]); c.imprimirLinea([] == []);"), "Ylista=[null]\nfalse\n");
        salida(proyecto("list<int> devolver(list<int> xs) { xs[0] += 1; return xs; } int recorrer(list<int> xs) { repeat each (int x in xs) { return x; } return -1; }", "list<int> xs = [2, 3]; c.imprimirLinea(devolver(xs) == xs); c.imprimirLinea(recorrer(xs)); c.imprimirLinea(recorrer([])); c.imprimirLinea(xs);"), "true\n3\n-1\n[3, 3]\n");
        salida(programa("list<bool> bs = [true, false]; list<list<float>> fs = [[1, 2], [], [3.5]]; c.imprimirLinea(bs); c.imprimirLinea(fs); list<int> nil = null; c.imprimirLinea(nil); c.imprimirLinea(nil == null);"), "[true, false]\n[[1.0, 2.0], [], [3.5]]\nnull\ntrue\n");
        salida(programa("let xs = [1, 2]; xs[0] = 7; c.imprimirLinea(xs); c.imprimirLinea(\"vacías=\" + [[], []]); c.imprimirLinea(\"nulos=\" + [[null], []]);"), "[7, 2]\nvacías=[[], []]\nnulos=[[null], []]\n");
    }
    private static void objetos() throws IOException {
        salida("import runtime.Consola; { public class Caja { public int n; public float f; public bool b; public string s; public list<int> xs; public Caja otra; public int aumentar(int x) { n += x; return n; } } public class Main { public main() { Consola c = new Consola(); Caja a = new Caja(); Caja b = a; c.imprimirLinea(a.n); c.imprimirLinea(a.f); c.imprimirLinea(a.b); c.imprimirLinea(a.s); c.imprimirLinea(a.xs); c.imprimirLinea(a.otra); b.n = 7; c.imprimirLinea(a.aumentar(2)); c.imprimirLinea(a == b); c.imprimirLinea(a == new Caja()); c.imprimirLinea(a); } } }", "0\n0.0\nfalse\nnull\nnull\nnull\n9\ntrue\nfalse\n<Caja>\n");
        salida("import runtime.Consola; { public class Base { int x = 2; int privado() { return x; } public int valor() { return privado(); } public int base() { return x; } } public class Hija extends Base { int x = 8; public int valor() { return x; } public int propia() { return x; } } public class Main { public main() { Consola c = new Consola(); Hija h = new Hija(); Base b = h; c.imprimirLinea(b.valor()); c.imprimirLinea(b.base()); c.imprimirLinea(h.propia()); c.imprimirLinea(b == h); c.imprimirLinea(b); } } }", "8\n2\n8\ntrue\n<Hija>\n");
        salida("import runtime.Consola; { public class Base { public int antes = leer(); public int leer() { return 1; } } public class Hija extends Base { public int despues = 7; public int leer() { return despues; } } public class Main { public main() { Consola c = new Consola(); Hija h = new Hija(); c.imprimirLinea(h.antes); c.imprimirLinea(h.despues); } } }", "0\n7\n");
        salida(proyecto("int a = b + 1; int b = 5; int d = a + b;", "c.imprimirLinea(a); c.imprimirLinea(b); c.imprimirLinea(d);"), "1\n5\n6\n");
        salida("import runtime.Consola; { public class C { public int n = 4; void C() { n = 99; } } public class Main { public main() { Consola c = new Consola(); C a = new C(); C b = new C(); a.n = 10; c.imprimirLinea(b.n); c.imprimirLinea(a != b); } } }", "4\ntrue\n");
        salida("import runtime.Consola; { public class B { public B mismo() { return new B(); } } public class D extends B { public D mismo() { return new D(); } } public class Main { public main() { Consola c = new Consola(); B b = new D(); B r = b.mismo(); c.imprimirLinea(r); } } }", "<D>\n");
        salida("import runtime.Consola; { public class Base { public int n = 2; } public class D extends Base { public int n = 5; } public class Main { public main() { Consola c = new Consola(); D d = new D(); Base b = d; b.n += 1; d.n += 2; c.imprimirLinea(b.n); c.imprimirLinea(d.n); list<D> ds = [d]; repeat each (Base x in ds) { x.n += 4; } c.imprimirLinea(b.n); c.imprimirLinea(ds); } } }", "3\n7\n7\n[<D>]\n");
        salida("import runtime.Consola; { public class Nodo { public int n; public Nodo siguiente; } public class Main { public main() { Consola c = new Consola(); Nodo a = new Nodo(); Nodo b = new Nodo(); a.siguiente = b; b.siguiente = a; a.siguiente.n = 3; c.imprimirLinea(b.n); c.imprimirLinea(b.siguiente == a); c.imprimirLinea(a); } } }", "3\ntrue\n<Nodo>\n");
        salida("import runtime.Consola; { public class Main extends Base { int n = 9; public int leer() { return n; } public main() { Consola c = new Consola(); c.imprimirLinea(antes); c.imprimirLinea(leer()); } } public class Base { public int antes = leer(); public int leer() { return 1; } } }", "0\n9\n");
        salida("import runtime.Consola; { public class B { int privado() { return 1; } public int llamar() { return privado(); } public int elegir(B b) { return 3; } } public class D extends B { public int privado() { return 2; } public int elegir(D d) { return 4; } } public class Main { public main() { Consola c = new Consola(); D d = new D(); B b = d; c.imprimirLinea(b.llamar()); c.imprimirLinea(d.privado()); c.imprimirLinea(b.elegir(d)); c.imprimirLinea(d.elegir(d)); list<B> xs = [d, new B(), null]; c.imprimirLinea(xs); } } }", "1\n2\n3\n4\n[<D>, <B>, null]\n");
        salida("{ public class Consola { public int leerInt() { return 7; } } public class Main { public main() { Consola c = new Consola(); if (c.leerInt() != 7) { int fallo = 1 / 0; } } } }", "");
    }
    private static void orden() throws IOException {
        salida(proyecto("list<int> xs = [3]; list<int> lista(Consola c) { c.imprimir(\"L\"); return xs; } int indice(Consola c) { c.imprimir(\"I\"); return 0; } int valor(Consola c) { c.imprimir(\"V\"); xs[0] = 100; return 4; }", "lista(c)[indice(c)] += valor(c); c.imprimirLinea(xs);"), "LIV[7]\n");
        salida("import runtime.Consola; { public class C { public int n = 3; } public class Main { C obj = new C(); C objeto(Consola c) { c.imprimir(\"R\"); return obj; } int valor(Consola c) { c.imprimir(\"V\"); obj.n = 100; return 4; } public main() { Consola c = new Consola(); objeto(c).n += valor(c); c.imprimirLinea(obj.n); } } }", "RV7\n");
        salida(proyecto("int elemento(Consola c, int n) { c.imprimir(n); return n; } list<int> lista(Consola c) { c.imprimir(\"L\"); return [1, 2]; }", "list<int> xs = [elemento(c, 1), elemento(c, 2)]; repeat each (int x in lista(c)) { c.imprimir(x); } Exist(lista(c)) { c.imprimir(\"E\"); } c.imprimirLinea(xs);"), "12L12LE[1, 2]\n");
        salida("import runtime.Consola; { public class B { Consola c = new Consola(); int a = efecto(1); int efecto(int n) { c.imprimir(n); return n; } } public class D extends B { Consola c = new Consola(); int b = efecto(2); int efecto(int n) { c.imprimir(n); return n; } } public class Main { public main() { D d = new D(); } } }", "12");
    }
    private static void errores() throws IOException {
        for (String indice : Arrays.asList("-1", "1", "9223372036854775807")) caso(programa("list<int> xs = [2]; c.imprimir(\"A\"); c.imprimirLinea(xs[" + indice + "]);"), "A", "INDICE_FUERA_DE_RANGO", Long.MAX_VALUE);
        caso(programa("list<int> xs = null; c.imprimirLinea(xs[0]);"), "", "REFERENCIA_NULA", Long.MAX_VALUE);
        caso(programa("list<int> xs = null; Exist(xs) { c.imprimir(\"X\"); }"), "", "REFERENCIA_NULA", Long.MAX_VALUE);
        caso(programa("list<int> xs = null; repeat each (int x in xs) { c.imprimir(x); }"), "", "REFERENCIA_NULA", Long.MAX_VALUE);
        caso("import runtime.Consola; { public class C { public int n; } public class Main { public main() { C obj = null; obj.n = 3; } } }", "", "REFERENCIA_NULA", Long.MAX_VALUE);
        caso(proyecto("list<int> xs = null; int indice(Consola c) { c.imprimir(\"I\"); return 0; } int valor(Consola c) { c.imprimir(\"V\"); return 2; }", "xs[indice(c)] = valor(c);"), "I", "REFERENCIA_NULA", Long.MAX_VALUE);
        caso(proyecto("list<int> xs = [1]; int valor(Consola c) { c.imprimir(\"V\"); return 2; }", "xs[8] = valor(c);"), "", "INDICE_FUERA_DE_RANGO", Long.MAX_VALUE);
        caso(programa("list<int> xs = [1];"), "", "MEMORIA_AGOTADA", 32);
        caso("{ public class C { C siguiente = new C(); } public class Main { public main() { C c = new C(); } } }", "", "MEMORIA_AGOTADA", 128);
        caso("import runtime.Consola; { public class C { public int metodo(int n) { return n; } } public class Main { int argumento(Consola c) { c.imprimir(\"A\"); return 1; } public main() { Consola c = new Consola(); C objeto = null; c.imprimirLinea(objeto.metodo(argumento(c))); } } }", "A", "REFERENCIA_NULA", Long.MAX_VALUE);
    }
    private static void imports() throws IOException {
        Path dir = Files.createTempDirectory("h3-imports-");
        try {
            Path aux = dir.resolve("Aux.txt"), principal = dir.resolve("Main.txt");
            Files.writeString(aux, "{ public class Base { public int n = 2; public int valor() { return n; } } public class Aux { public main() {} } }");
            String s = "import Aux; import runtime.Consola; { public class D extends Base { public int valor() { return 7; } } public class Main { public main() { Consola c = new Consola(); Base b = new D(); c.imprimirLinea(b.valor()); c.imprimirLinea(b.n); } } }";
            Files.writeString(principal, s);
            ResultadoGeneracionIr r = new GeneradorIr().generar(Main.analizarProyecto(principal, s, true), "Main");
            comprobar(r.esExitoso(), "Composición de imports H3 " + r.errores);
            Resultado t = correr(r.modulo, false, Long.MAX_VALUE), a = correr(r.modulo, true, Long.MAX_VALUE);
            comprobar(t.error == null && a.error == null && Arrays.equals(t.salida, a.salida) && new String(t.salida, StandardCharsets.UTF_8).equals("7\n2\n"), "Herencia/inicialización/dispatch entre unidades");
            Files.writeString(aux, "{ public class Base { public int n = 1 / 0; public int valor() { return n; } } public class Aux { public main() {} } }");
            r = new GeneradorIr().generar(Main.analizarProyecto(principal, s, true), "Main");
            comprobar(r.esExitoso(), "Error de ejecución en inicializador importado conserva generación válida");
            t = correr(r.modulo, false, Long.MAX_VALUE); a = correr(r.modulo, true, Long.MAX_VALUE);
            comprobar(t.error != null && a.error != null && t.error.codigo.equals("DIVISION_POR_CERO")
                    && t.error.diagnostico().equals(a.error.diagnostico()) && t.error.origen.archivo.equals(aux.toString()), "Error conserva origen de unidad importada");
        } finally { Files.deleteIfExists(dir.resolve("Aux.txt")); Files.deleteIfExists(dir.resolve("Main.txt")); Files.delete(dir); }
    }
    private static void metadatos() throws IOException {
        ModuloIr m = generar(programa(""));
        List<ClaseIr> cs = new ArrayList<>();
        for (ClaseIr c : m.clases) cs.add(c.nombre.equals("Main") ? new ClaseIr(c.nombre, c.base, c.campos, c.consolaIntegrada, m.entrada) : c);
        ModuloIr invalido = new ModuloIr(m.nombre, cs, m.funciones, m.entrada);
        comprobar(new VerificadorIr().verificar(invalido).getErrores().stream().anyMatch(e -> e.codigo.equals("INICIALIZADOR_INVALIDO")), "El main no puede suplantar al inicializador");
        ModuloAtomicoIr a = new LinealizadorIr().transformar(m);
        Map<String, LayoutProgramaIr.Clase> clases = new LinkedHashMap<>(a.layout.clases);
        LayoutProgramaIr.Clase c = clases.get("Main");
        clases.put("Main", new LayoutProgramaIr.Clase(c.nombre, c.base, c.consolaIntegrada, c.tamano, c.campos, c.tiposCampos, c.virtuales, m.entrada));
        comprobar(new VerificadorAtomicoIr().verificar(new ModuloAtomicoIr(a.nombre, a.entrada, new LayoutProgramaIr(clases, a.layout.marcos), a.funciones)).stream()
                .anyMatch(e -> e.contains("INICIALIZADOR_INVALIDO")), "Verificador físico rechaza inicializador adulterado");
    }
}
