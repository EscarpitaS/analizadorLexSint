import java.util.*;

/** Verifica identidad de enlaces y la salida estructurada, no solamente ausencia de errores. */
public class PruebasAstDecorado {
    public static void main(String[] args) { ejecutar(); System.out.println("OK: enlaces, ambitos, conversiones y resultado validado."); }
    static void ejecutar() {
        String fuente = "{ class A { public int campo; public float f(float n) { return n; } } "
                + "class Main extends A { public float f(float n) { while (n > 0) { if (n > 1) { return 1; } else { return n; } } return 0; } "
                + "void recibir(list<list<float>> xs) { } public main() { "
                + "int campo = 2; float x = campo; A a = new Main(); float y = a.f(1); "
                + "recibir([[1], [2.5]]); list<int> ns = [1]; repeat each (float v in ns) { x += v; } } } }";
        NodoArbolSintactico arbol = parsear(fuente);
        Semantico motor = new Semantico();
        ResultadoSemantico resultado = motor.analizar(arbol);
        exigir(resultado.esExitoso(), resultado.getErrores().toString());
        exigir(resultado.getAstValidado() == arbol, "No entrega el AST validado.");
        List<NodoArbolSintactico> nodos = nodos(arbol, false);
        List<Semantico.Simbolo> simbolos = simbolos(resultado.getAmbitoGlobal());
        exigir(simbolos.stream().anyMatch(s -> s.rol == Semantico.RolSimbolo.PARAMETRO), "No persistieron parámetros.");
        exigir(simbolos.stream().anyMatch(s -> s.rol == Semantico.RolSimbolo.ITERADOR), "No persistió el iterador.");
        exigir(simbolos.stream().filter(s -> s.nombre.equals("campo")).count() == 2, "No conserva campo y local separados.");
        for (NodoArbolSintactico nodo : nodos) {
            exigir(nodo.getAmbitoResuelto() != null, "Nodo sin ámbito: " + nodo.getTexto());
            if (nodo.getNombre().equals("IDENTIFICADOR") || nodo.getNombre().equals("PARAMETRO")) {
                exigir(nodo.getSimboloResuelto() != null, "Referencia sin símbolo: " + nodo.getTexto());
                exigir(simbolos.contains(nodo.getSimboloResuelto()), "Símbolo fuera del árbol de ámbitos.");
            }
            if (nodo.getNombre().equals("IDENTIFICADOR") && "campo".equals(nodo.getValor()))
                exigir(nodo.getSimboloResuelto().rol == (nodo.getSimboloResuelto().nodoDeclaracion.getNombre().equals("MIEMBRO")
                        ? Semantico.RolSimbolo.CAMPO : Semantico.RolSimbolo.VARIABLE), "Shadowing incorrecto.");
            if (nodo.getNombre().equals("NEW")) {
                exigir(nodo.getClaseConstruida() == motor.getClase("Main"), "Construcción sin clase resuelta.");
                exigir(nodo.getSimboloResuelto() == motor.getClase("Main").constructor, "Constructor sin identidad.");
            }
            if (nodo.getNombre().equals("LLAMADA") && nodo.getSimboloResuelto().nombre.equals("f")) {
                exigir(nodo.getSimboloResuelto().propietaria == motor.getClase("A"), "Firma estática incorrecta.");
                exigir(nodo.getDespacho() == NodoArbolSintactico.Despacho.VIRTUAL, "Debe preservar despacho virtual.");
                exigir(nodo.getReceptor() != null && nodo.getTipoReceptor().equals(Semantico.clase("A")), "Falta receptor estático.");
            }
        }
        long conversiones = nodos(arbol, true).stream().filter(n -> n.getNombre().equals("CONVERSION_IMPLICITA")).count();
        exigir(conversiones >= 7, "Faltan promociones/asignaciones/argumentos/retornos/iteración: " + conversiones);
        exigir(!arbol.imprimir().contains("CONVERSION_IMPLICITA"), "Se alteró el árbol sintáctico.");
        String decorado = arbol.imprimirSemantico();
        motor.analizar(arbol);
        exigir(decorado.equals(arbol.imprimirSemantico()), "Reanálisis duplica o pierde decoraciones.");
        ResultadoSemantico simple = new Semantico().analizar(parsear(programa("float x = 1;")));
        NodoArbolSintactico init = nodos(simple.getAstValidado(), false).stream()
                .filter(n -> n.getNombre().equals("INICIALIZACION")).findFirst().get();
        exigir(init.getHijos().get(0).getTipoSemantico().equals(Semantico.INT), "Se perdió el tipo fuente.");
        exigir(init.getHijosSemanticos().get(0).getNombre().equals("CONVERSION_IMPLICITA")
                && init.getHijosSemanticos().get(0).getTipoSemantico().equals(Semantico.FLOAT), "Conversión int->float ausente.");
        ResultadoSemantico directa = new Semantico().analizar(parsear("{ class Main { private int f() { return 1; } public main() { int x = f(); } } }"));
        exigir(nodos(directa.getAstValidado(), false).stream().filter(n -> n.getNombre().equals("LLAMADA"))
                .allMatch(n -> n.getDespacho() == NodoArbolSintactico.Despacho.DIRECTO && n.getReceptor() == null),
                "La llamada privada debe conservar receptor implícito y despacho directo.");
        ResultadoSemantico contextoRetorno = new Semantico().analizar(parsear("{ class Main { list<list<float>> f() { return [[1], [2.5]]; } public main() { } } }"));
        exigir(contextoRetorno.esExitoso(), "El retorno no usa el mismo contexto de listas.");
        ResultadoSemantico sinContaminar = new Semantico().analizar(parsear("{ class Main { int f(bool b) { int x; if (b) { return 1; x = 2; } else { x = 3; } return x; } public main() { } } }"));
        exigir(sinContaminar.getErrores().size() == 1 && sinContaminar.getErrores().get(0).mensaje.contains("inalcanzable"),
                "El estado inalcanzable contaminó la rama que continúa: " + sinContaminar.getErrores());
        ResultadoSemantico cascada = new Semantico().analizar(parsear(programa("var x = falta;")));
        exigir(cascada.getErrores().size() == 1, "Cascada de inferencia: " + cascada.getErrores());
        exigir(!cascada.esExitoso() && cascada.getAstValidado() == null && cascada.getArbolDiagnostico() != null,
                "Expone como validado un AST con errores.");
        ResultadoSemantico muerto = new Semantico().analizar(parsear(programa("return; bool x = 1; int y = falta;")));
        exigir(muerto.getErrores().size() == 3, "No acumula los errores del bloque inalcanzable: " + muerto.getErrores());
        exigir(nodos(muerto.getArbolDiagnostico(), false).stream().anyMatch(NodoArbolSintactico::esInalcanzable), "Falta marca de inalcanzable.");
        ResultadoSemantico parcial = new Semantico().analizar(parsearSinExigir("{ class Main { } }"));
        exigir(!parcial.esExitoso() && parcial.getAstValidado() == null, "Sintaxis inválida aprobada.");
        ResultadoSemantico invariancia = new Semantico().analizar(parsear("{ class Main { void f(list<float> xs) { } public main() { list<int> xs = [1]; f(xs); } } }"));
        exigir(!invariancia.esExitoso(), "Se permitió convertir una lista mutable existente.");
        ResultadoSemantico ambigua = new Semantico().analizar(parsear("{ class Main { void f(list<int> xs) { } void f(list<float> xs) { } public main() { f([]); } } }"));
        exigir(ambigua.getErrores().size() == 1 && ambigua.getErrores().get(0).mensaje.contains("ambigua"), "Se perdió la ambigüedad contextual.");
    }
    private static String programa(String cuerpo) { return "{ class Main { public main() { " + cuerpo + " } } }"; }
    private static NodoArbolSintactico parsear(String fuente) {
        Lexico l = new Lexico(fuente); l.tokenizar();
        AnalizadorSintactico p = new AnalizadorSintactico(l.getTokens(), new Gramatica()); p.analizar();
        exigir(!l.hasErrors() && !p.hasErrors(), "Prueba mal formada: " + p.getErrores()); return p.getArbol();
    }
    private static NodoArbolSintactico parsearSinExigir(String fuente) {
        Lexico l = new Lexico(fuente); l.tokenizar(); AnalizadorSintactico p = new AnalizadorSintactico(l.getTokens(), new Gramatica()); p.analizar(); return p.getArbol();
    }
    private static List<NodoArbolSintactico> nodos(NodoArbolSintactico raiz, boolean semantico) {
        List<NodoArbolSintactico> lista = new ArrayList<>(); lista.add(raiz);
        for (NodoArbolSintactico hijo : semantico ? raiz.getHijosSemanticos() : raiz.getHijos()) lista.addAll(nodos(hijo, semantico));
        return lista;
    }
    private static List<Semantico.Simbolo> simbolos(Semantico.AmbitoSimbolos ambito) {
        List<Semantico.Simbolo> lista = new ArrayList<>(ambito.getSimbolos());
        for (Semantico.AmbitoSimbolos hijo : ambito.getHijos()) lista.addAll(simbolos(hijo)); return lista;
    }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
