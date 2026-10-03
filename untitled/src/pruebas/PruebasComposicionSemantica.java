import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Composiciones derivadas de GRAMATICA_CANONICA.md y CONTRATO_SEMANTICO.md.
 * Todas las entradas deben pasar lexer y parser; el oráculo semántico es el contrato.
 * No depende de JUnit ni de una pantalla. Ejecuta todos los casos antes de informar fallos.
 */
public class PruebasComposicionSemantica {
    private final List<String> fallos = new ArrayList<>();
    private final Map<String, Integer> cobertura = new LinkedHashMap<>();
    private final Semantico motor = new Semantico();
    private int total;
    private int validos;
    private int invalidos;

    public static void main(String[] args) { ejecutar(); }

    static void ejecutar() {
        PruebasComposicionSemantica suite = new PruebasComposicionSemantica();
        suite.listasContextuales();
        suite.listasYHerencia();
        suite.sobrecargasYDespacho();
        suite.flujoYAmbitos();
        suite.expresionesYDestinos();
        suite.diagnosticosYSalida();
        System.out.println("Composición semántica: " + (suite.total - suite.fallos.size()) + "/" + suite.total
                + " aprobados (" + suite.validos + " válidos, " + suite.invalidos + " inválidos).");
        for (Map.Entry<String, Integer> grupo : suite.cobertura.entrySet())
            System.out.println("  " + grupo.getKey() + ": " + grupo.getValue() + " casos");
        if (!suite.fallos.isEmpty()) {
            for (String fallo : suite.fallos) System.err.println(fallo);
            throw new AssertionError("Fallaron " + suite.fallos.size() + " composiciones semánticas.");
        }
    }

    // T.2, S.4-S.8, R.1-R.2, E.15/E.18/E.19: mismo literal en cinco destinos.
    private void listasContextuales() {
        for (int profundidad = 1; profundidad <= 3; profundidad++) {
            String tipo = lista("float", profundidad);
            String literal = anidar("[1, 2.5]", profundidad - 1);
            String incompatible = anidar("[true]", profundidad - 1);
            for (String destino : Arrays.asList("campo", "declaracion", "asignacion", "retorno", "argumento")) {
                String etiqueta = "profundidad " + profundidad + ", " + destino;
                acepta("Listas contextuales", etiqueta, enDestino(destino, tipo, "(" + literal + ")"), r -> {
                    long promociones = nodos(r.getAstValidado(), true).stream()
                            .filter(n -> "CONVERSION_IMPLICITA".equals(n.getNombre()))
                            .filter(n -> n.getTipoSemantico().equals(Semantico.FLOAT)).count();
                    exigir(promociones >= 1, "No se conservó la promoción del elemento int a float.");
                });
                rechaza("Listas contextuales", etiqueta + ", elemento bool",
                        enDestino(destino, tipo, incompatible),
                        "argumento".equals(destino) ? "firma accesible" : "Elemento incompatible");
            }
            // Las referencias mutables existentes no reciben la conversión contextual del literal.
            String origen = lista("int", profundidad);
            String inicio = origen + " origen = " + anidar("[1]", profundidad - 1) + "; ";
            rechaza("Invariancia", "declaracion profundidad " + profundidad,
                    programa("", inicio + tipo + " destino = origen;"), "asignar");
            rechaza("Invariancia", "asignacion profundidad " + profundidad,
                    programa("", inicio + tipo + " destino = []; destino = origen;"), "asignar");
            rechaza("Invariancia", "argumento profundidad " + profundidad,
                    programa("void recibir(" + tipo + " datos) { }", inicio + "recibir(origen);"), "firma accesible");
            rechaza("Invariancia", "retorno profundidad " + profundidad,
                    programa(tipo + " devolver(" + origen + " datos) { return datos; }", ""), "retorno");
        }
        acepta("Listas contextuales", "vacios y null en tres niveles",
                programa("", "list<list<list<float>>> xs = [[], [[]], [[1], null]]; xs[2][0][0] += 1;"));
        acepta("Listas contextuales", "resultado de llamada como elemento y argumento",
                programa("int uno() { return 1; } void recibir(list<list<float>> xs) { }",
                        "recibir([[uno()], [2.5]]);"));
        acepta("Listas contextuales", "inferir promocion y reutilizar tipo inferido",
                programa("void recibir(list<float> xs) { }", "var xs = [1, 2.5]; recibir(xs); xs[0] += 1;"));
        rechaza("Listas contextuales", "var anidado solo null",
                programa("", "var xs = [[null], []];"), "inferir");
        rechaza("Listas contextuales", "llamada void dentro de lista anidada",
                programa("void vacio() { }", "list<list<int>> xs = [[vacio()]];"), "Elemento incompatible");
    }

    // C.1/C.3, T.1/T.2, E.15: subtipos y listas, incluidos tipos declarados después.
    private void listasYHerencia() {
        String clases = "class Hija extends Base { } class Otra extends Base { } class Base { } ";
        acepta("Listas y herencia", "literal de hermanos con tipo base comun",
                clasesPrograma(clases, "void recibir(list<Base> xs) { }",
                        "var xs = [new Hija(), null, new Otra()]; recibir(xs);"));
        acepta("Listas y herencia", "literal contextual de subtipos anidado",
                clasesPrograma(clases, "list<list<Base>> crear() { return [[new Hija()], [new Otra(), null]]; }",
                        "list<list<Base>> xs = crear(); Base b = xs[0][0];"));
        acepta("Listas y herencia", "iterador subtipo a base con despacho",
                clasesPrograma("class Base { public int valor() { return 1; } } class Hija extends Base { }", "",
                        "list<Hija> xs = [new Hija()]; repeat each (Base b in xs) { int n = b.valor(); }"), r -> {
                    NodoArbolSintactico ciclo = primero(r, "CICLO");
                    exigir(ciclo.getSimboloResuelto().tipo.equals(Semantico.clase("Base")), "Tipo incorrecto del iterador.");
                    exigir(nodos(ciclo, true).stream().anyMatch(n -> "CONVERSION_IMPLICITA".equals(n.getNombre())
                            && n.getTipoSemantico().equals(Semantico.clase("Base"))), "Falta conversión del elemento a Base.");
                });
        rechaza("Listas y herencia", "invariancia de lista de subtipos",
                clasesPrograma(clases, "", "list<Hija> xs = [new Hija()]; list<Base> ys = xs;"), "asignar");
        rechaza("Listas y herencia", "invariancia de listas anidadas de subtipos",
                clasesPrograma(clases, "void recibir(list<list<Base>> xs) { }",
                        "list<list<Hija>> xs = [[new Hija()]]; recibir(xs);"), "firma accesible");
        rechaza("Listas y herencia", "iteracion base a derivada",
                clasesPrograma(clases, "", "list<Base> xs = [new Base()]; repeat each (Hija h in xs) { }"), "repeat each");
        rechaza("Listas y herencia", "literal contextual con clase ajena",
                clasesPrograma(clases + "class Ajena { }", "", "list<list<Base>> xs = [[new Ajena()]];"), "Elemento incompatible");
        rechaza("Listas y herencia", "ciclo de tres clases y lista tipada",
                clasesPrograma("class A extends B { } class B extends C { } class C extends A { }", "",
                        "list<A> xs = [new A()];"), "ciclo");
    }

    // C.5-C.10, E.16-E.19: elección de firma, covarianza y visibilidad.
    private void sobrecargasYDespacho() {
        String familia = "class Base { } class Hija extends Base { } class Nieta extends Hija { } ";
        String metodos = "int elegir(Base b) { return 1; } float elegir(Hija h) { return 2.5; }";
        acepta("Sobrecargas y herencia", "subtipo transitivo elige Hija",
                clasesPrograma(familia, metodos, "float n = elegir(new Nieta());"),
                r -> firma(r, "elegir", "Main", "Hija"));
        acepta("Sobrecargas y herencia", "null elige subtipo unico",
                clasesPrograma(familia, metodos, "float n = elegir(null);"),
                r -> firma(r, "elegir", "Main", "Hija"));
        acepta("Sobrecargas y herencia", "eleccion usa tipo estatico del argumento",
                clasesPrograma(familia, metodos, "Base b = new Nieta(); int n = elegir(b);"),
                r -> firma(r, "elegir", "Main", "Base"));
        acepta("Sobrecargas y herencia", "sobrecarga numerica con llamada anidada",
                programa("int uno() { return 1; } int elegir(int n) { return n; } bool elegir(float n) { return true; }",
                        "int n = elegir(uno());"), r -> firma(r, "elegir", "Main", "int"));
        acepta("Sobrecargas y herencia", "literal distingue lista por sus elementos",
                programa("int elegir(list<list<int>> xs) { return 1; } bool elegir(list<list<bool>> xs) { return true; }",
                        "int n = elegir([[1], []]);"), r -> firma(r, "elegir", "Main", "list<list<int>>"));
        rechaza("Sobrecargas y herencia", "literal numerico ambiguo por invariancia",
                programa("void elegir(list<list<int>> xs) { } void elegir(list<list<float>> xs) { }",
                        "elegir([[1]]);"), "ambigua");
        rechaza("Sobrecargas y herencia", "vacios agrupados ambiguos",
                programa("void elegir(list<list<int>> xs) { } void elegir(list<list<bool>> xs) { }",
                        "elegir(([[]]));"), "ambigua");
        rechaza("Sobrecargas y herencia", "null entre clases sin relacion",
                clasesPrograma("class A { } class B { }", "void elegir(A a) { } void elegir(B b) { }",
                        "elegir(null);"), "ambigua");
        rechaza("Sobrecargas y herencia", "parametros cruzados sin mejor firma",
                clasesPrograma(familia, "void elegir(Base a, Hija b) { } void elegir(Hija a, Base b) { }",
                        "elegir(new Nieta(), new Nieta());"), "ambigua");
        acepta("Sobrecargas y herencia", "override covariante encadenado y firma estatica",
                clasesPrograma("class Base { public Base crear() { return new Base(); } public int valor() { return 1; } } "
                        + "class Hija extends Base { public Hija crear() { return new Hija(); } public int valor() { return 2; } }", "",
                        "Base b = new Hija(); int n = b.crear().valor();"), r -> {
                    firma(r, "crear", "Base");
                    firma(r, "valor", "Base");
                    for (NodoArbolSintactico n : nodos(r.getAstValidado(), false)) if ("LLAMADA".equals(n.getNombre()))
                        exigir(n.getDespacho() == NodoArbolSintactico.Despacho.VIRTUAL && n.getReceptor() != null,
                                "Se perdió receptor o despacho virtual.");
                });
        acepta("Sobrecargas y herencia", "override no duplica candidatas",
                "{ class Base { public int elegir(int n) { return n; } } "
                        + "class Main extends Base { public int elegir(int n) { return n + 1; } public main() { int n = elegir(1); } } }",
                r -> firma(r, "elegir", "Main", "int"));
        acepta("Sobrecargas y herencia", "hereda sobrecarga y agrega otra",
                "{ class Base { public bool elegir(float n) { return true; } } "
                        + "class Main extends Base { public int elegir(int n) { return n; } public main() { int n = elegir(1); bool b = elegir(1.5); } } }",
                r -> {
                    List<NodoArbolSintactico> llamadas = llamadas(r, "elegir");
                    exigir(llamadas.size() == 2, "Faltan llamadas a las dos sobrecargas.");
                    exigir(llamadas.get(0).getSimboloResuelto().propietaria.simbolo.nombre.equals("Main")
                            && llamadas.get(1).getSimboloResuelto().propietaria.simbolo.nombre.equals("Base"),
                            "No distinguió la sobrecarga local de la heredada.");
                });
        acepta("Sobrecargas y herencia", "metodo privado de base no es override",
                clasesPrograma("class Base { private int f(int n) { return n; } } class Hija extends Base { private bool f(int n) { return true; } }", "", ""));
        rechaza("Sobrecargas y herencia", "reduccion de visibilidad con retorno covariante",
                clasesPrograma("class Base { public Base crear() { return new Base(); } } "
                        + "class Hija extends Base { private Hija crear() { return new Hija(); } }", "", ""), "visibilidad");
        rechaza("Sobrecargas y herencia", "override no permite convertir lista de retorno",
                clasesPrograma("class Base { public list<Base> crear() { return []; } } "
                        + "class Hija extends Base { public list<Hija> crear() { return []; } }", "", ""), "retorno");
        rechaza("Sobrecargas y herencia", "retorno no distingue firma con listas anidadas",
                programa("int f(list<list<int>> a) { return 1; } bool f(list<list<int>> b) { return true; }", ""), "firma igual");
    }

    // I.1/I.2, CL.1-CL.3, I.3, R.1: intersección de rutas y ámbitos anidados.
    private void flujoYAmbitos() {
        acepta("Flujo y ambitos", "asignacion completa en if anidados",
                programa("int f(bool a, bool b) { int x; if (a) { if (b) { x = 1; } else { x = 2; } } else { x = 3; } return x; }", "int n = f(true, false);"));
        acepta("Flujo y ambitos", "rama interna retorna y otra inicializa",
                programa("int f(bool a, bool b) { int x; if (a) { if (b) { return 1; } else { x = 2; } } else { x = 3; } return x; }", ""));
        acepta("Flujo y ambitos", "else if retorna o inicializa",
                programa("int f(bool a, bool b) { int x; if (a) { return 1; } else if (b) { x = 2; } else { x = 3; } return x; }", ""));
        rechaza("Flujo y ambitos", "una ruta interna no inicializa",
                programa("int f(bool a, bool b) { int x; if (a) { if (b) { x = 1; } } else { x = 2; } return x; }", ""), "antes de inicializarse");
        rechaza("Flujo y ambitos", "else if sin else no garantiza inicializacion",
                programa("int f(bool a, bool b) { int x; if (a) { return 1; } else if (b) { x = 2; } return x; }", ""), "antes de inicializarse");
        for (String control : Arrays.asList("while (true)", "repeat (true)", "repeat each (int item in [1])", "Exist([1])")) {
            rechaza("Flujo y ambitos", control + " no garantiza asignacion",
                    programa("", "int x; " + control + " { if (true) { x = 1; } else { x = 2; } } int y = x;"), "antes de inicializarse");
            rechaza("Flujo y ambitos", control + " no garantiza retorno",
                    programa("int f() { " + control + " { if (true) { return 1; } else { return 2; } } }", ""), "sin retornar");
        }
        acepta("Flujo y ambitos", "retorno contextual de lista en todas las rutas",
                programa("list<list<float>> f(bool b) { if (b) { return [[1]]; } else { return [[], [2.5]]; } }",
                        "list<list<float>> xs = f(true);"));
        rechaza("Flujo y ambitos", "retorno de lista incompatible en una ruta",
                programa("list<list<float>> f(bool b) { if (b) { return [[1]]; } else { return [[true]]; } }", ""), "Elemento incompatible");
        acepta("Flujo y ambitos", "iterador visible en control anidado",
                programa("", "list<int> xs = [1]; repeat each (float item in xs) { if (item > 0) { float y = item; } else { float y = item; } }"));
        rechaza("Flujo y ambitos", "iterador sale del ambito tras control anidado",
                programa("", "repeat each (int item in [1]) { if (true) { int y = item; } } int z = item;"), "No existe el identificador");
        rechaza("Flujo y ambitos", "iterador no oculta parametro",
                programa("void f(int item) { repeat each (int item in [1]) { } }", ""), "iterador");
        acepta("Flujo y ambitos", "ocultar campo y reutilizar nombres en bloques hermanos",
                programa("int x;", "if (true) { int x = 1; } else { int x = 2; } int x = 3;"));
        rechaza("Flujo y ambitos", "local no oculta parametro en bloque interno",
                programa("void f(int x) { if (true) { int x = 1; } }", ""), "ya fue declarada");
        acepta("Flujo y ambitos", "campo inicializado y parametros de llamada hacia adelante",
                programa("float valor = uno(); int uno() { return 1; } float f(float n) { return valor + n; }", "float n = f(1);"));
        rechaza("Flujo y ambitos", "autorreferencia en lista inferida",
                programa("", "var xs = [xs];"), "antes de inicializarse");
    }

    // S.6-S.8 y E.1-E.19: llamadas, índices y operadores dentro de controles.
    private void expresionesYDestinos() {
        acepta("Expresiones y destinos", "precedencia not sobre comparacion y logica",
                programa("int uno() { return 1; }", "bool b = not uno() < 2 && (true || false);"));
        acepta("Expresiones y destinos", "llamada devuelve objeto con campo lista",
                clasesPrograma("class Caja { public list<float> xs = [1]; }", "Caja crear() { return new Caja(); }",
                        "crear().xs[0] += 1; float n = crear().xs[0];"));
        acepta("Expresiones y destinos", "llamada devuelve lista y destino indexado",
                programa("list<float> crear() { return [1]; }", "crear()[0] = 2; crear()[0] *= 2;"));
        acepta("Expresiones y destinos", "let permite modificar objeto y lista",
                clasesPrograma("class Caja { public int n; }", "",
                        "let c = new Caja(); c.n = 1; let xs = [1]; xs[0] += 1;"));
        rechaza("Expresiones y destinos", "let no permite sustituir lista",
                programa("", "let xs = [1]; xs = [2];"), "constante");
        rechaza("Expresiones y destinos", "indice bool en acceso doble",
                programa("", "list<list<int>> xs = [[1]]; int n = xs[0][true];"), "índice");
        rechaza("Expresiones y destinos", "asignacion compuesta int con resultado float",
                programa("", "list<int> xs = [1]; xs[0] += 1.5;"), "asignar");
        rechaza("Expresiones y destinos", "receptor sin inicializar en destino campo",
                clasesPrograma("class Caja { public int n; }", "", "Caja c; c.n = 1;"), "antes de inicializarse");
        rechaza("Expresiones y destinos", "lista sin inicializar como destino indexado",
                programa("", "list<int> xs; xs[0] = 1;"), "antes de inicializarse");
        rechaza("Expresiones y destinos", "campo privado mediante receptor derivado",
                clasesPrograma("class Base { int n; } class Hija extends Base { }", "",
                        "Hija h = new Hija(); h.n = 1;"), "privado");
        rechaza("Expresiones y destinos", "concat con llamada sin valor",
                programa("void vacio() { }", "string s = \"n=\" + vacio();"), "operador");
        rechaza("Expresiones y destinos", "constructor con argumentos conserva error interno",
                clasesPrograma("class Caja { }", "", "Caja c = new Caja(falta);"),
                "constructor implícito", "No existe el identificador");
    }

    private void diagnosticosYSalida() {
        rechaza("Diagnosticos y salida", "errores independientes tras retorno de ambas ramas",
                programa("void f(bool b) { if (b) { return; } else { return; } bool x = 1; int y = falta; }", ""),
                "inalcanzable", "asignar", "No existe el identificador");
        rechaza("Diagnosticos y salida", "inalcanzable no inicializa otra ruta",
                programa("int f(bool b) { int x; if (b) { return 1; x = 2; } return x; }", ""),
                "inalcanzable", "antes de inicializarse");
        rechaza("Diagnosticos y salida", "error de inferencia no provoca cascada",
                programa("", "var xs = [falta];"), "No existe el identificador");
        rechaza("Diagnosticos y salida", "argumento anidado inexistente no repite firma",
                programa("void f(list<list<int>> xs) { }", "f([[falta]]);"), "No existe el identificador");
        acepta("Diagnosticos y salida", "conversiones sobre argumento retorno y operacion",
                programa("float doble(float n) { return n * 2; }", "float n = doble(1); n += 1;"), r -> {
                    NodoArbolSintactico asignacion = primero(r, "ASIGNACION");
                    exigir(asignacion.getTipoOperacion().equals(Semantico.FLOAT), "Operación compuesta sin tipo float.");
                    NodoArbolSintactico llamada = llamadas(r, "doble").get(0);
                    exigir(llamada.getDespacho() == NodoArbolSintactico.Despacho.DIRECTO,
                            "El método privado debe tener despacho directo.");
                    exigir(nodos(r.getAstValidado(), true).stream().filter(n -> "CONVERSION_IMPLICITA".equals(n.getNombre())).count() == 3,
                            "Faltan o sobran conversiones int->float en argumento, producto y asignación.");
                });
    }

    private void acepta(String grupo, String nombre, String fuente) { acepta(grupo, nombre, fuente, r -> {}); }
    private void acepta(String grupo, String nombre, String fuente, Consumer<ResultadoSemantico> comprobar) {
        caso(grupo, nombre, fuente, new String[0], comprobar);
    }
    private void rechaza(String grupo, String nombre, String fuente, String... mensajes) {
        caso(grupo, nombre, fuente, mensajes, r -> {});
    }
    private void caso(String grupo, String nombre, String fuente, String[] mensajes, Consumer<ResultadoSemantico> comprobar) {
        total++;
        if (mensajes.length == 0) validos++; else invalidos++;
        cobertura.merge(grupo, 1, Integer::sum);
        try {
            Lexico lexico = new Lexico(fuente);
            lexico.tokenizar();
            exigir(!lexico.hasErrors(), "Entrada de prueba fuera del contrato léxico: " + lexico.getErrors());
            AnalizadorSintactico parser = new AnalizadorSintactico(lexico.getTokens(), new Gramatica());
            parser.analizar();
            exigir(!parser.hasErrors(), "Entrada de prueba fuera de la gramática: " + parser.getErrores());
            NodoArbolSintactico arbol = parser.getArbol();
            String sintaxis = arbol.imprimir();
            ResultadoSemantico resultado = motor.analizar(arbol);
            comprobarResultado(resultado, mensajes);
            exigir(sintaxis.equals(arbol.imprimir()), "El análisis semántico alteró el árbol sintáctico.");
            if (mensajes.length == 0) validarSalida(resultado);
            comprobar.accept(resultado);
            String decorado = arbol.imprimirSemantico();
            String diagnosticos = resultado.getErrores().toString();
            // Segunda ejecución comprueba limpieza de símbolos y decoraciones en el mismo AST.
            ResultadoSemantico repetido = motor.analizar(arbol);
            comprobarResultado(repetido, mensajes);
            exigir(decorado.equals(arbol.imprimirSemantico()) && diagnosticos.equals(repetido.getErrores().toString()),
                    "El reanálisis cambió los diagnósticos o duplicó decoraciones.");
            if (mensajes.length == 0) validarSalida(repetido);
            comprobar.accept(repetido);
        } catch (AssertionError | RuntimeException e) {
            fallos.add("FALLO [" + grupo + "] " + nombre + "\n" + e + "\nFuente:\n" + fuente + "\n");
        }
    }

    private static void comprobarResultado(ResultadoSemantico resultado, String[] mensajes) {
        exigir(resultado.getErrores().size() == mensajes.length,
                "Se esperaban " + mensajes.length + " diagnósticos, recibidos: " + resultado.getErrores());
        // Asociación uno a uno: un diagnóstico no puede satisfacer dos expectativas.
        List<compiladorErrores> pendientes = new ArrayList<>(resultado.getErrores());
        for (String mensaje : mensajes) {
            int encontrado = -1;
            for (int i = 0; i < pendientes.size(); i++) if (pendientes.get(i).mensaje.contains(mensaje)) { encontrado = i; break; }
            exigir(encontrado >= 0, "Falta diagnóstico '" + mensaje + "': " + resultado.getErrores());
            pendientes.remove(encontrado);
        }
        for (compiladorErrores error : resultado.getErrores()) {
            exigir(error.TIPOERROR == compiladorErrores.TIPO_ERROR.SEMANTICO, "Fase de diagnóstico incorrecta.");
            exigir(error.linea > 0 && error.columna > 0, "Diagnóstico sin ubicación: " + error);
        }
        exigir(resultado.esExitoso() == (mensajes.length == 0), "Estado de éxito incompatible con los diagnósticos.");
        exigir((resultado.getAstValidado() != null) == (mensajes.length == 0), "Se expone u oculta incorrectamente el AST validado.");
        exigir(resultado.getArbolDiagnostico() != null && resultado.getAmbitoGlobal() != null, "Falta árbol de diagnóstico o ámbitos.");
    }

    private static void validarSalida(ResultadoSemantico resultado) {
        Set<Semantico.Simbolo> simbolos = Collections.newSetFromMap(new IdentityHashMap<Semantico.Simbolo, Boolean>());
        recolectarSimbolos(resultado.getAmbitoGlobal(), simbolos);
        Set<String> expresiones = new java.util.HashSet<>(Arrays.asList("ENTERO", "FLOAT", "STRING", "IDENTIFICADOR",
                "NEW", "LLAMADA", "ACCESO_MIEMBRO", "ACCESO_ARREGLO", "ARREGLO_LITERAL", "AGRUPACION", "UNARIO",
                "EXPRESION", "CONVERSION_IMPLICITA", "ELEMENTO_ITERADO"));
        for (NodoArbolSintactico nodo : nodos(resultado.getAstValidado(), true)) {
            exigir(nodo.getAmbitoResuelto() != null, "Nodo sin ámbito: " + nodo.getTexto());
            if (expresiones.contains(nodo.getNombre()))
                exigir(nodo.getTipoSemantico() != null && !nodo.getTipoSemantico().equals(Semantico.ERROR), "Expresión sin tipo válido: " + nodo.getTexto());
            if (nodo.getSimboloResuelto() != null)
                exigir(simbolos.contains(nodo.getSimboloResuelto()), "Símbolo ajeno a los ámbitos persistentes.");
            if (Arrays.asList("IDENTIFICADOR", "PARAMETRO", "LLAMADA", "NEW", "ACCESO_MIEMBRO").contains(nodo.getNombre()))
                exigir(nodo.getSimboloResuelto() != null, "Referencia sin símbolo: " + nodo.getTexto());
            if ("NEW".equals(nodo.getNombre()))
                exigir(nodo.getDespacho() == NodoArbolSintactico.Despacho.CONSTRUCTOR && nodo.getClaseConstruida() != null,
                        "Construcción sin clase o despacho.");
            if ("CONVERSION_IMPLICITA".equals(nodo.getNombre()))
                exigir(nodo.getHijosSemanticos().size() == 1, "Conversión sin un único operando.");
        }
    }

    private static void recolectarSimbolos(Semantico.AmbitoSimbolos ambito, Set<Semantico.Simbolo> salida) {
        salida.addAll(ambito.getSimbolos());
        for (Semantico.AmbitoSimbolos hijo : ambito.getHijos()) recolectarSimbolos(hijo, salida);
    }
    private static List<NodoArbolSintactico> nodos(NodoArbolSintactico raiz, boolean semanticos) {
        List<NodoArbolSintactico> salida = new ArrayList<>();
        salida.add(raiz);
        for (NodoArbolSintactico hijo : semanticos ? raiz.getHijosSemanticos() : raiz.getHijos()) salida.addAll(nodos(hijo, semanticos));
        return salida;
    }
    private static NodoArbolSintactico primero(ResultadoSemantico resultado, String nombre) {
        return nodos(resultado.getAstValidado(), false).stream().filter(n -> nombre.equals(n.getNombre())).findFirst()
                .orElseThrow(() -> new AssertionError("Falta nodo " + nombre));
    }
    private static List<NodoArbolSintactico> llamadas(ResultadoSemantico resultado, String nombre) {
        List<NodoArbolSintactico> salida = new ArrayList<>();
        for (NodoArbolSintactico nodo : nodos(resultado.getAstValidado(), false))
            if ("LLAMADA".equals(nodo.getNombre()) && nodo.getSimboloResuelto().nombre.equals(nombre)) salida.add(nodo);
        return salida;
    }
    private static void firma(ResultadoSemantico resultado, String nombre, String propietaria, String... parametros) {
        List<NodoArbolSintactico> llamadas = llamadas(resultado, nombre);
        exigir(llamadas.size() == 1, "Se esperaba una llamada a " + nombre);
        Semantico.Simbolo elegido = llamadas.get(0).getSimboloResuelto();
        exigir(elegido.propietaria.simbolo.nombre.equals(propietaria), "Clase declaradora incorrecta para " + nombre);
        exigir(elegido.parametros.toString().equals(Arrays.asList(parametros).toString()), "Firma incorrecta: " + elegido.parametros);
    }
    private static String lista(String base, int profundidad) {
        for (int i = 0; i < profundidad; i++) base = "list<" + base + ">";
        return base;
    }
    private static String anidar(String literal, int niveles) {
        for (int i = 0; i < niveles; i++) literal = "[" + literal + "]";
        return literal;
    }
    private static String enDestino(String destino, String tipo, String literal) {
        switch (destino) {
            case "campo": return programa(tipo + " datos = " + literal + ";", "");
            case "declaracion": return programa("", tipo + " datos = " + literal + ";");
            case "asignacion": return programa("", tipo + " datos = []; datos = " + literal + ";");
            case "retorno": return programa(tipo + " crear() { return " + literal + "; }", tipo + " datos = crear();");
            case "argumento": return programa("void recibir(" + tipo + " datos) { }", "recibir(" + literal + ");");
            default: throw new IllegalArgumentException(destino);
        }
    }
    private static String programa(String miembros, String cuerpo) { return clasesPrograma("", miembros, cuerpo); }
    private static String clasesPrograma(String clases, String miembros, String cuerpo) {
        return "{\n" + clases + "\nclass Main {\n" + miembros + "\npublic main() {\n" + cuerpo + "\n}\n}\n}";
    }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
