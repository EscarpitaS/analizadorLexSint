import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Entradas adversas con procesos aislados y watchdog por caso, sin bibliotecas externas. */
public class PruebasEntradasMalformadas {
    private enum Expectativa { VALIDA, LEXICA, SINTACTICA, MUTACION }
    private static final int TAMANO_LOTE = 48;
    private static final int LIMITE_SEGUNDOS = 5;
    private static final long SEMILLA = 20260929L;

    private static final class Caso {
        final String grupo, nombre, fuente;
        final Expectativa expectativa;
        final String recuperado;
        Caso(String grupo, String nombre, String fuente, Expectativa expectativa, String recuperado) {
            this.grupo = grupo; this.nombre = nombre; this.fuente = fuente;
            this.expectativa = expectativa; this.recuperado = recuperado;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--worker".equals(args[0])) {
            List<Caso> casos = construirCasos();
            for (int i = Integer.parseInt(args[1]); i < Integer.parseInt(args[2]); i++) {
                try {
                    String fase = verificar(casos.get(i));
                    System.out.println("OK " + i + " " + fase);
                    System.out.flush();
                } catch (Throwable fallo) {
                    // Un Error (incluido desbordamiento de pila) es un fallo de la suite.
                    // Se termina este proceso para no reutilizar un motor posiblemente dañado.
                    fallo.printStackTrace(System.err);
                    String mensaje = Base64.getEncoder().encodeToString(fallo.toString().getBytes(StandardCharsets.UTF_8));
                    System.out.println("FALLO " + i + " " + mensaje);
                    System.out.flush();
                    System.exit(1);
                }
            }
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Uso: java PruebasEntradasMalformadas");
        ejecutar();
    }

    static void ejecutar() {
        List<Caso> casos = construirCasos();
        List<String> fallos = new ArrayList<>();
        Map<String, Integer> grupos = new LinkedHashMap<>();
        Map<String, Integer> fases = new LinkedHashMap<>();
        Path carpeta = null;
        try {
            carpeta = Files.createTempDirectory("malformadas-supervisor-");
            Path log = carpeta.resolve("worker.log");
            String java = Paths.get(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            for (int inicio = 0; inicio < casos.size(); inicio += TAMANO_LOTE) {
                int fin = Math.min(casos.size(), inicio + TAMANO_LOTE);
                int siguiente = inicio;
                while (siguiente < fin) {
                    Process proceso = new ProcessBuilder(java, "-Xmx128m", "-Xss1m", "-Djava.awt.headless=true",
                            "-Djava.io.tmpdir=" + carpeta, "-cp", System.getProperty("java.class.path"), PruebasEntradasMalformadas.class.getName(),
                            "--worker", Integer.toString(siguiente), Integer.toString(fin))
                            .redirectError(log.toFile()).start();
                    ExecutorService lector = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "watchdog-malformadas"); t.setDaemon(true); return t;
                    });
                    try (BufferedReader salida = new BufferedReader(new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
                        while (siguiente < fin) {
                            Caso caso = casos.get(siguiente);
                            grupos.merge(caso.grupo, 1, Integer::sum);
                            Future<String> linea = lector.submit(salida::readLine);
                            String respuesta;
                            try {
                                respuesta = linea.get(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
                            } catch (TimeoutException e) {
                                proceso.destroyForcibly();
                                linea.cancel(true);
                                fallos.add(detalle(caso, "Bloqueo: excedió " + LIMITE_SEGUNDOS + " segundos."));
                                siguiente++;
                                break;
                            }
                            String ok = "OK " + siguiente + " ";
                            String error = "FALLO " + siguiente + " ";
                            if (respuesta != null && respuesta.startsWith(ok)) {
                                fases.merge(respuesta.substring(ok.length()), 1, Integer::sum);
                                siguiente++;
                            } else {
                                String motivo = respuesta != null && respuesta.startsWith(error)
                                        ? new String(Base64.getDecoder().decode(respuesta.substring(error.length())), StandardCharsets.UTF_8)
                                        : "El proceso terminó sin completar el caso: " + respuesta + "\n" + leerLog(log);
                                fallos.add(detalle(caso, motivo));
                                siguiente++;
                                break;
                            }
                        }
                    } finally {
                        proceso.destroyForcibly();
                        proceso.waitFor(1, TimeUnit.SECONDS);
                        lector.shutdownNow();
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("No se pudo ejecutar el supervisor de entradas malformadas.", e);
        } finally {
            if (carpeta != null) {
                try (java.util.stream.Stream<Path> archivos = Files.walk(carpeta)) {
                    for (Path archivo : (Iterable<Path>) archivos.sorted(java.util.Comparator.reverseOrder())::iterator)
                        Files.delete(archivo);
                } catch (Exception e) { throw new AssertionError(e); }
            }
        }
        System.out.println("Entradas malformadas: " + (casos.size() - fallos.size()) + "/" + casos.size() + " aprobadas; semilla " + SEMILLA + ".");
        for (Map.Entry<String, Integer> grupo : grupos.entrySet()) System.out.println("  " + grupo.getKey() + ": " + grupo.getValue());
        System.out.println("  Resultados observados: " + fases);
        if (!fallos.isEmpty()) {
            for (String fallo : fallos) System.err.println(fallo);
            throw new AssertionError("Fallaron " + fallos.size() + " entradas; no se aceptan bloqueos ni excepciones.");
        }
    }

    private static String leerLog(Path log) throws Exception {
        try (BufferedReader lector = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            char[] contenido = new char[4096];
            int n = lector.read(contenido);
            return n < 0 ? "" : new String(contenido, 0, n);
        }
    }
    private static String detalle(Caso caso, String motivo) {
        return "FALLO [" + caso.grupo + "] " + caso.nombre + "\n" + motivo + "\nFuente:\n" + caso.fuente + "\n";
    }

    private static List<Caso> construirCasos() {
        List<Caso> casos = new ArrayList<>();
        for (String fuente : Arrays.asList("", " ", "// solo comentario", "\uFEFF", "{", "{}", "}", "{{}}",
                "import", "import A", "import A.", "import ;", "import .;", "import A..B;",
                "public class Main", "{ class", "{ class Main", "{ class Main extends", "{ class Main {",
                "{ class Main { public", "{ class Main { public main", "{ class Main { public main(",
                "{ class Main { public main() {", "{ class Main { void f(", "{ class Main { list<",
                "{ class Main { list<list<", "{ class Main { int x =", "{ class Main { int x = ["))
            agregar(casos, "EOF y estructura", "fragmento " + casos.size(), fuente, Expectativa.SINTACTICA, null);

        for (String fragmento : Arrays.asList("@", ":", "!", "&", "|", "_", "á", "1nombre", "1.", "\"sin cierre",
                "\"cadena\notra", "\"cadena\rotra", "\"cadena\r\notra", "int x = @;", "string s = \"sin cierre\n;"))
            agregar(casos, "Errores lexicos", "fragmento " + casos.size(), programa(fragmento), Expectativa.LEXICA, null);

        for (String fragmento : Arrays.asList("int x = ;", "bool b = ;", "int x = (1 + );", "int x = -( );",
                "bool b = not ;", "int x = 1 int y = 2;", "var x = ;", "let x = ;", "list<int> xs = [1 2];",
                "list<int> xs = [1,];", "list<int> xs = [,1];", "list<int> xs = [[1],];",
                "list<int> xs = [1;", "int x = f(1 2);", "int x = f(1,);", "int x = f(,1);",
                "int x = f(1;", "f()", "x = ;", "x += ;", "x[ ] = 1;", "x[0 = 1;", "x. = 1;",
                "if true { }", "if () { }", "if (true { }", "while true { }", "repeat true { }",
                "repeat each int item in [1] { }", "repeat each (int in [1]) { }", "Exist [1] { }",
                "return 1 + ;", "else { }", "else if (true) { } else { }", "else", "public int x;", "; ; ;"))
            agregar(casos, "Recuperacion de sentencias", "fragmento " + casos.size(),
                    programa(fragmento + "\nint recuperado = 7; bool errorPosterior = 1;"), Expectativa.SINTACTICA, "recuperado");

        for (String miembro : Arrays.asList("return valor;", "int campo = ;", "int campo = 1", "void f int x { }",
                "void f(int x,) { }", "void f(int x string y) { }", "list<int campo;", "public private int x;"))
            agregar(casos, "Recuperacion de miembros", "miembro " + casos.size(),
                    "{ class Main { " + miembro + "\npublic main() { int recuperado = 7; bool errorPosterior = 1; } } }",
                    Expectativa.SINTACTICA, "recuperado");
        for (String cabecera : Arrays.asList("import local extra;", "import local", "import A.;", "import .A;", "import A..B;"))
            agregar(casos, "Recuperacion de imports", cabecera,
                    cabecera + "\n" + programa("int recuperado = 7; bool errorPosterior = 1;"), Expectativa.SINTACTICA, "recuperado");

        String base = programa("int n = 1; list<list<float>> xs = [[1], []]; if (n < 2) { n += 1; } else { n = 2; }");
        String llamadas = "{ class Caja { public int n; public list<int> f(int x) { return [x]; } } "
                + "class Main { public main() { Caja c = new Caja(); list<int> xs = c.f(1); xs[0] += c.n; } } }";
        String ciclos = programa("list<int> xs = [1, 2]; repeat each (float item in xs) { while (item > 0) { item -= 1; } } Exist(xs) { repeat (false) { xs[0] = 1; } }");
        String firmas = "import java.util.List; { class Hija extends Base { public float f(float x, bool b) { if (b) { return x; } else { return 1; } } } "
                + "class Base { public float f(float x, bool b) { return x; } } class Main { public main() { Base b = new Hija(); float n = b.f(1, true); } } }";
        int numero = 0;
        for (String semilla : Arrays.asList(base, llamadas, ciclos, firmas)) {
            agregar(casos, "Controles validos", "semilla " + numero, semilla, Expectativa.VALIDA, null);
            Lexico lexico = new Lexico(semilla); lexico.tokenizar();
            List<Token> tokens = lexico.getTokens();
            int n = tokens.size() - 1;
            for (int i = 0; i < n; i++) {
                agregar(casos, "Truncamientos por token", "semilla " + numero + ", corte " + i,
                        reconstruir(tokens.subList(0, i)), Expectativa.SINTACTICA, null);
                List<Token> borrado = new ArrayList<>(tokens.subList(0, n)); borrado.remove(i);
                agregar(casos, "Eliminaciones por token", "semilla " + numero + ", token " + i,
                        reconstruir(borrado), Expectativa.MUTACION, null);
            }
            numero++;
        }
        // Semilla fija: ruido del vocabulario canónico y del alfabeto inválido.
        Random random = new Random(SEMILLA);
        String[] ruido = { "{", "}", "(", ")", "[", "]", ";", ",", ".", "<", ">", "=", "+=", "not", "&&",
                "class", "main", "public", "void", "int", "list", "if", "else", "repeat", "each", "in", "return", "new",
                "import", "extends", "dato", "1", "true", "null", "\"texto\"", "@", "!", "1.", "1nombre", "_" };
        for (int i = 0; i < 100; i++) {
            StringBuilder fuente = new StringBuilder();
            int longitud = 1 + random.nextInt(48);
            for (int j = 0; j < longitud; j++) fuente.append(ruido[random.nextInt(ruido.length)]).append(' ');
            agregar(casos, "Ruido reproducible", "muestra " + i, i % 2 == 0 ? programa(fuente.toString()) : fuente.toString(), Expectativa.MUTACION, null);
        }
        for (String finLinea : Arrays.asList("\n", "\r", "\r\n")) {
            String nombreFin = finLinea.equals("\n") ? "LF" : finLinea.equals("\r") ? "CR" : "CRLF";
            agregar(casos, "Finales de linea", "cadena incompleta con " + nombreFin,
                    programa("string s = \"abierta" + finLinea + "int siguiente = 1;"), Expectativa.LEXICA, null);
            agregar(casos, "Finales de linea", "recuperacion con " + nombreFin,
                    programa("int x = ;\nint recuperado = 7; bool errorPosterior = 1;").replace("\n", finLinea),
                    Expectativa.SINTACTICA, "recuperado");
        }
        // Profundidad moderada, acotada; no se promete aceptar anidamiento ilimitado.
        String anidado = "1";
        for (int i = 0; i < 32; i++) anidado = "(" + anidado + ")";
        agregar(casos, "Anidamiento acotado", "32 grupos validos", programa("int x = " + anidado + ";"), Expectativa.VALIDA, null);
        agregar(casos, "Anidamiento acotado", "grupo exterior sin cierre", programa("int x = (" + anidado + ";"), Expectativa.SINTACTICA, null);
        return casos;
    }
    private static void agregar(List<Caso> casos, String grupo, String nombre, String fuente, Expectativa expectativa, String recuperado) {
        casos.add(new Caso(grupo, nombre, fuente, expectativa, recuperado));
    }
    private static String programa(String cuerpo) { return "{\nclass Main {\npublic main() {\n" + cuerpo + "\n}\n}\n}"; }
    private static String reconstruir(List<Token> tokens) {
        StringBuilder salida = new StringBuilder();
        for (Token token : tokens) salida.append(token.lexema).append(";{}".contains(token.lexema) ? '\n' : ' ');
        return salida.toString();
    }

    private static String verificar(Caso caso) throws Exception {
        Lexico lexico = new Lexico(caso.fuente);
        lexico.tokenizar();
        String tokens = lexico.getTokens().toString(), erroresLexicos = lexico.getErrors().toString();
        exigir(lexico.getTokens().stream().filter(t -> t.tipo == tipoToken.EOF).count() == 1, "EOF ausente o duplicado.");
        ubicaciones(lexico.getErrors());
        lexico.tokenizar();
        exigir(tokens.equals(lexico.getTokens().toString()) && erroresLexicos.equals(lexico.getErrors().toString()),
                "El reanálisis léxico acumuló tokens o cambió diagnósticos.");
        if (caso.expectativa == Expectativa.LEXICA) exigir(lexico.hasErrors(), "Falta el rechazo léxico esperado.");
        if (caso.expectativa == Expectativa.SINTACTICA || caso.expectativa == Expectativa.VALIDA)
            exigir(!lexico.hasErrors(), "El caso no llegó limpio al parser: " + lexico.getErrors());

        AnalizadorSintactico parser = null;
        if (!lexico.hasErrors()) {
            parser = new AnalizadorSintactico(lexico.getTokens(), new Gramatica());
            parser.analizar();
            if (caso.expectativa == Expectativa.SINTACTICA) exigir(parser.hasErrors(), "Falta el rechazo sintáctico esperado.");
            if (caso.expectativa == Expectativa.VALIDA) exigir(!parser.hasErrors(), "Se rechazó una semilla válida.");
            ubicaciones(parser.getErrores());
            String sintaxis = parser.getArbol().imprimir(), erroresSintacticos = parser.getErrores().toString();
            if (parser.hasErrors()) exigir(parser.getArbol().contieneSintaxisIncompleta(), "No se marcó el AST parcial.");
            Semantico motor = new Semantico();
            ResultadoSemantico semantico = motor.analizar(parser.getArbol());
            ubicaciones(semantico.getErrores());
            if (parser.hasErrors()) exigir(!semantico.esExitoso() && semantico.getAstValidado() == null,
                    "Se expuso un AST validado pese a los errores sintácticos.");
            if (caso.recuperado != null) {
                exigir(tieneDeclaracion(parser.getArbol(), caso.recuperado), "Se perdió la declaración posterior " + caso.recuperado);
                exigir(semantico.getErrores().stream().anyMatch(e -> e.lexema.equals("errorPosterior") && e.mensaje.contains("asignar")),
                        "Se omitió el error semántico independiente después del fragmento dañado.");
            }
            String decorado = parser.getArbol().imprimirSemantico();
            String erroresSemanticos = semantico.getErrores().toString();
            // También ejercita las vistas de diagnóstico sobre el árbol recuperado.
            AnalizadorLexicoUI.crearNodoSwing(parser.getArbol(), false);
            AnalizadorLexicoUI.crearNodoSwing(parser.getArbol(), true);
            parser.analizar();
            exigir(sintaxis.equals(parser.getArbol().imprimir()) && erroresSintacticos.equals(parser.getErrores().toString()),
                    "El parser no se reinició correctamente.");
            motor.analizar(parser.getArbol());
            exigir(decorado.equals(parser.getArbol().imprimirSemantico()) && erroresSemanticos.equals(motor.getErrores().toString()),
                    "La semántica parcial no es reproducible.");
        }
        Path archivo = Files.createTempFile("malformada-", ".txt");
        try {
            Files.writeString(archivo, caso.fuente);
            List<Main.ResultadoAnalisis> resultados = Main.analizarProyecto(archivo, false);
            exigir(resultados.size() == 1, "El análisis de una unidad cargó archivos adicionales.");
            Main.ResultadoAnalisis resultado = resultados.get(0);
            if (lexico.hasErrors()) exigir(resultado.sintactico == null && resultado.semantico == null,
                    "Los errores léxicos deben detener las fases posteriores.");
            boolean invalido = !resultado.diagnosticos().isEmpty();
            exigir(resultado.resultadoSemantico.esExitoso() == !invalido, "Éxito global incompatible con los diagnósticos.");
            exigir((resultado.resultadoSemantico.getAstValidado() == null) == invalido, "Salida validada incorrecta.");
            if (caso.expectativa == Expectativa.VALIDA) exigir(!invalido, "Falló una semilla válida: " + resultado.diagnosticos());
            ubicaciones(resultado.diagnosticos());
            String reporte = Main.generarReporte(resultados);
            exigir(reporte.contains("TOTAL DE ERRORES: " + resultado.diagnosticos().size()), "El reporte perdió diagnósticos.");
            exigir(AnalizadorLexicoUI.crearErrores(resultado).size() == resultado.diagnosticos().size(), "El modelo visual perdió diagnósticos.");
            if (lexico.hasErrors()) return "LEXICO";
            if (parser.hasErrors()) return "SINTACTICO";
            return resultado.semantico.hasErrors() ? "SEMANTICO" : "VALIDO";
        } finally { Files.deleteIfExists(archivo); }
    }
    private static void ubicaciones(List<compiladorErrores> errores) {
        for (compiladorErrores e : errores) exigir(e.linea > 0 && e.columna > 0, "Diagnóstico sin ubicación: " + e);
    }
    private static boolean tieneDeclaracion(NodoArbolSintactico nodo, String nombre) {
        if ("DECLARACION".equals(nodo.getNombre()) && nodo.getHijos().stream()
                .anyMatch(h -> "IDENTIFICADOR".equals(h.getNombre()) && nombre.equals(h.getValor()))) return true;
        for (NodoArbolSintactico hijo : nodo.getHijos()) if (tieneDeclaracion(hijo, nombre)) return true;
        return false;
    }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
