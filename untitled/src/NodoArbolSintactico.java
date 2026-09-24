import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class NodoArbolSintactico {
    private final String nombre;
    private final String valor;
    private final List<NodoArbolSintactico> hijos = new ArrayList<>();
    private int linea;
    private int columna;
    private Semantico.Tipo tipoSemantico;
    private Semantico.Simbolo simboloResuelto;
    private Semantico.AmbitoSimbolos ambitoResuelto;
    private Semantico.ClaseInfo claseConstruida;
    enum Despacho { NINGUNO, DIRECTO, VIRTUAL, CONSTRUCTOR }
    private Despacho despacho = Despacho.NINGUNO;
    private boolean inalcanzable;
    private Semantico.Tipo tipoOperacion;
    private Semantico.Tipo tipoReceptor;
    private NodoArbolSintactico receptor;
    private final List<NodoArbolSintactico> hijosAdicionales = new ArrayList<>();
    void agregarHijoSemantico(NodoArbolSintactico nodo) { hijosAdicionales.add(nodo); }
    Semantico.Tipo getTipoReceptor() { return tipoReceptor; }
    NodoArbolSintactico getReceptor() { return receptor; }
    void anotarReceptor(Semantico.Tipo tipo, NodoArbolSintactico nodo) { tipoReceptor = tipo; receptor = nodo; }
    private final java.util.Map<NodoArbolSintactico, NodoArbolSintactico> conversiones = new java.util.IdentityHashMap<>();

    Semantico.Simbolo getSimboloResuelto() { return simboloResuelto; }
    void enlazarSimbolo(Semantico.Simbolo simbolo) { simboloResuelto = simbolo; }
    Semantico.AmbitoSimbolos getAmbitoResuelto() { return ambitoResuelto; }
    void enlazarAmbito(Semantico.AmbitoSimbolos ambito) { ambitoResuelto = ambito; }
    Semantico.ClaseInfo getClaseConstruida() { return claseConstruida; }
    void enlazarConstruccion(Semantico.ClaseInfo clase) { claseConstruida = clase; despacho = Despacho.CONSTRUCTOR; }
    Despacho getDespacho() { return despacho; }
    void anotarDespacho(Despacho valor) { despacho = valor; }
    boolean esInalcanzable() { return inalcanzable; }
    void marcarInalcanzable() { inalcanzable = true; for (NodoArbolSintactico hijo : hijos) hijo.marcarInalcanzable(); }
    Semantico.Tipo getTipoOperacion() { return tipoOperacion; }
    void anotarTipoOperacion(Semantico.Tipo tipo) { tipoOperacion = tipo; }

    /** Aristas del AST decorado: las conversiones no alteran el árbol sintáctico. */
    List<NodoArbolSintactico> getHijosSemanticos() {
        List<NodoArbolSintactico> resultado = new ArrayList<>();
        for (NodoArbolSintactico hijo : hijos) resultado.add(conversiones.getOrDefault(hijo, hijo));
        for (NodoArbolSintactico hijo : hijosAdicionales) resultado.add(conversiones.getOrDefault(hijo, hijo));
        return Collections.unmodifiableList(resultado);
    }
    void convertirHijo(NodoArbolSintactico hijo, Semantico.Tipo destino) {
        if (!hijos.contains(hijo) && !hijosAdicionales.contains(hijo)) throw new IllegalArgumentException("La conversión requiere un hijo directo");
        NodoArbolSintactico conversion = new NodoArbolSintactico("CONVERSION_IMPLICITA",
                hijo.getTipoSemantico() + " -> " + destino, hijo.linea, hijo.columna);
        conversion.agregar(hijo);
        conversion.anotarTipoSemantico(destino);
        conversion.enlazarAmbito(hijo.ambitoResuelto);
        if (hijo.inalcanzable) conversion.marcarInalcanzable();
        conversiones.put(hijo, conversion);
    }
    private boolean sintaxisIncompleta;
    private final List<String> accionesSemanticas = new ArrayList<>();

    void marcarSintaxisIncompleta() { sintaxisIncompleta = true; }
    boolean esSintaxisIncompleta() { return sintaxisIncompleta || nombre.startsWith("ERROR"); }
    boolean contieneSintaxisIncompleta() {
        if (esSintaxisIncompleta()) return true;
        for (NodoArbolSintactico hijo : hijos) if (hijo.contieneSintaxisIncompleta()) return true;
        return false;
    }
    void accion(String accion) {
        if (!accionesSemanticas.contains(accion)) accionesSemanticas.add(accion);
    }
    List<String> getAccionesSemanticas() { return Collections.unmodifiableList(accionesSemanticas); }
    void limpiarSemantica() {
        tipoSemantico = null; simboloResuelto = null; ambitoResuelto = null;
        claseConstruida = null; despacho = Despacho.NINGUNO; inalcanzable = false;
        tipoOperacion = null; tipoReceptor = null; receptor = null; hijosAdicionales.clear(); conversiones.clear(); accionesSemanticas.clear();
    }
    String getTextoVista(boolean semantico) {
        return getTexto() + (esSintaxisIncompleta() ? " [sintaxis incompleta]" : "")
                + (semantico && tipoSemantico != null ? " [tipo: " + tipoSemantico + "]" : "")
                + (semantico && simboloResuelto != null ? " [símbolo: " + simboloResuelto.nombre + "]" : "")
                + (semantico && despacho != Despacho.NINGUNO ? " [despacho: " + despacho + "]" : "")
                + (semantico && inalcanzable ? " [inalcanzable]" : "");
    }

    NodoArbolSintactico(String nombre) {
        this(nombre, null);
    }

    NodoArbolSintactico(String nombre, String valor) {
        this(nombre, valor, -1, -1);
    }

    NodoArbolSintactico(String nombre, String valor, int linea, int columna) {
        this.nombre = nombre;
        this.valor = valor;
        this.linea = linea;
        this.columna = columna;
    }

    void agregar(NodoArbolSintactico hijo) {
        if (hijo != null) {
            hijos.add(hijo);
            if (linea < 0 && hijo.linea > 0) {
                linea = hijo.linea;
                columna = hijo.columna;
            }
        }
    }

    List<NodoArbolSintactico> getHijos() {
        return Collections.unmodifiableList(hijos);
    }

    String getTexto() {
        if (valor == null || valor.isEmpty()) return nombre;
        return nombre + ": " + valor;
    }

    /** Nombre estable del nodo para recorridos posteriores al parser. */
    String getNombre() { return nombre; }

    /** Valor semántico del nodo; puede ser nulo en nodos estructurales. */
    String getValor() { return valor; }

    int getLinea() { return linea; }

    int getColumna() { return columna; }

    /** Tipo sintetizado por el análisis semántico; nulo antes de esa fase. */
    Semantico.Tipo getTipoSemantico() { return tipoSemantico; }

    void anotarTipoSemantico(Semantico.Tipo tipo) { this.tipoSemantico = tipo; }

    void ubicar(int linea, int columna) {
        if (this.linea < 0 && linea > 0) {
            this.linea = linea;
            this.columna = columna;
        }
    }

    String imprimir() { return imprimir(false, false); }
    String imprimirConAcciones() { return imprimir(false, true); }
    String imprimirSemantico() { return imprimir(true, true); }

    private String imprimir(boolean semantico, boolean acciones) {
        StringBuilder sb = new StringBuilder();
        imprimir(sb, "", true, semantico, acciones);
        return sb.toString();
    }

    private void imprimir(StringBuilder sb, String prefijo, boolean ultimo, boolean semantico, boolean acciones) {
        sb.append(prefijo).append(ultimo ? "`-- " : "|-- ").append(getTextoVista(semantico))
                .append(System.lineSeparator());
        String siguiente = prefijo + (ultimo ? "    " : "|   ");
        if (acciones) {
            for (int i = 0; i < accionesSemanticas.size(); i++) {
                boolean fin = hijos.isEmpty() && i == accionesSemanticas.size() - 1;
                sb.append(siguiente).append(fin ? "`-- " : "|-- ").append("{acción: ")
                        .append(accionesSemanticas.get(i)).append("}").append(System.lineSeparator());
            }
        }
        List<NodoArbolSintactico> visibles = semantico ? getHijosSemanticos() : hijos;
        for (int i = 0; i < visibles.size(); i++) {
            visibles.get(i).imprimir(sb, siguiente, i == visibles.size() - 1, semantico, acciones);
        }
    }
}
