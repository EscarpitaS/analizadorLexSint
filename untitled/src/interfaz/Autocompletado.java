import java.util.*;

/** Índice léxico tolerante: ayuda durante la edición sin exigir un AST válido. */
final class Autocompletado {
    static final class Sugerencia {
        final String texto, detalle;
        final boolean simbolo;
        Sugerencia(String texto, String detalle, boolean simbolo) {
            this.texto = texto; this.detalle = detalle; this.simbolo = simbolo;
        }
        @Override public String toString() { return texto + "    " + detalle; }
    }
    static final class Resultado {
        final int inicio, fin, cursor;
        final List<Sugerencia> sugerencias;
        Resultado(int inicio, int fin, int cursor, List<Sugerencia> sugerencias) {
            this.inicio = inicio; this.fin = fin; this.cursor = cursor;
            this.sugerencias = Collections.unmodifiableList(sugerencias);
        }
    }
    private enum ClaseAmbito { GLOBAL, CLASE, METODO, BLOQUE }
    private static final class Ambito {
        final Ambito padre;
        final ClaseAmbito clase;
        final int inicio;
        int fin;
        final List<Simbolo> simbolos = new ArrayList<>();
        Ambito(Ambito padre, ClaseAmbito clase, int inicio, int fin) {
            this.padre = padre; this.clase = clase; this.inicio = inicio; this.fin = fin;
        }
    }
    private static final class Simbolo {
        final Sugerencia sugerencia;
        final int posicion;
        final boolean adelantado;
        Simbolo(String nombre, String detalle, int posicion, boolean adelantado) {
            sugerencia = new Sugerencia(nombre, detalle, true);
            this.posicion = posicion; this.adelantado = adelantado;
        }
    }
    private static final class Unidad {
        final Token token;
        final int inicio, fin;
        Ambito ambito;
        Unidad(Token token, int inicio) { this.token = token; this.inicio = inicio; fin = inicio + token.lexema.length(); }
        String texto() { return token.lexema; }
        boolean id() { return token.tipo == tipoToken.IDENTIFICADOR; }
    }
    private static final class TipoLeido {
        final String nombre;
        final int siguiente;
        TipoLeido(String nombre, int siguiente) { this.nombre = nombre; this.siguiente = siguiente; }
    }

    static Resultado sugerir(String fuente, int cursor) {
        if (cursor < 0 || cursor > fuente.length()) throw new IllegalArgumentException("Cursor fuera del documento");
        int inicio = cursor, fin = cursor;
        while (inicio > 0 && identificador(fuente.charAt(inicio - 1))) inicio--;
        while (fin < fuente.length() && identificador(fuente.charAt(fin))) fin++;
        List<Sugerencia> salida = new ArrayList<>();
        if (enLiteralOComentario(fuente, cursor) || inicio < cursor && !letra(fuente.charAt(inicio))) return new Resultado(inicio, fin, cursor, salida);
        int anterior = inicio - 1;
        while (anterior >= 0 && Character.isWhitespace(fuente.charAt(anterior))) anterior--;
        // Los miembros de un receptor requieren resolución de tipos (un paso posterior).
        if (anterior >= 0 && fuente.charAt(anterior) == '.') return new Resultado(inicio, fin, cursor, salida);
        String prefijo = fuente.substring(inicio, cursor);
        String palabra = fuente.substring(inicio, fin);
        List<Unidad> tokens = tokenizar(fuente);
        Ambito global = new Ambito(null, ClaseAmbito.GLOBAL, 0, fuente.length());
        List<Ambito> ambitos = new ArrayList<>();
        ambitos.add(global);
        Map<Integer, Integer> parentesis = emparejar(tokens, "(", ")");
        Ambito actual = global;
        for (int i = 0; i < tokens.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return new Resultado(inicio, fin, cursor, salida);
            Unidad u = tokens.get(i);
            u.ambito = actual;
            if (u.texto().equals("{")) {
                ClaseAmbito clase = ClaseAmbito.BLOQUE;
                int comienzo = i - 1;
                while (comienzo >= 0 && !es(tokens, comienzo, "{") && !es(tokens, comienzo, "}") && !es(tokens, comienzo, ";")) comienzo--;
                boolean declaracionClase = false;
                for (int j = comienzo + 1; j < i; j++) if (es(tokens, j, "class")) declaracionClase = true;
                Integer apertura = parentesis.get(i - 1);
                boolean metodo = actual.clase == ClaseAmbito.CLASE && apertura != null && apertura > 0
                        && (tokens.get(apertura - 1).id() || es(tokens, apertura - 1, "main"));
                if (declaracionClase) clase = ClaseAmbito.CLASE;
                else if (metodo) clase = ClaseAmbito.METODO;
                Ambito nuevo = new Ambito(actual, clase, u.fin, fuente.length());
                ambitos.add(nuevo);
                if (apertura != null && (metodo || es(tokens, apertura - 1, "each"))) {
                    agregarParametros(tokens, apertura + 1, i - 1, nuevo, metodo);
                }
                actual = nuevo;
            } else if (u.texto().equals("}") && actual.padre != null) {
                actual.fin = u.inicio;
                actual = actual.padre;
            }
        }

        for (int i = 0; i < tokens.size(); i++) {
            Unidad u = tokens.get(i);
            if (es(tokens, i, "import")) {
                int j = i + 1;
                Unidad nombre = null;
                while (j < tokens.size() && !es(tokens, j, ";") && !es(tokens, j, "{")) {
                    if (tokens.get(j).id()) nombre = tokens.get(j);
                    j++;
                }
                if (nombre != null) global.simbolos.add(new Simbolo(nombre.texto(), "tipo importado", nombre.fin, true));
            }
            if (es(tokens, i, "class") && i + 1 < tokens.size() && tokens.get(i + 1).id()) {
                Unidad nombre = tokens.get(i + 1);
                u.ambito.simbolos.add(new Simbolo(nombre.texto(), "clase", nombre.fin, true));
            }
            if (u.ambito.clase == ClaseAmbito.CLASE && es(tokens, i, "main") && es(tokens, i + 1, "(")) {
                u.ambito.simbolos.add(new Simbolo("main", "método · void", u.fin, true));
            }
            if (!limiteDeclaracion(tokens, i)) continue;
            TipoLeido tipo = leerTipo(tokens, i);
            if (tipo == null || tipo.siguiente >= tokens.size() || !tokens.get(tipo.siguiente).id()) continue;
            Unidad nombre = tokens.get(tipo.siguiente);
            boolean miembro = u.ambito.clase == ClaseAmbito.CLASE;
            boolean metodo = miembro && es(tokens, tipo.siguiente + 1, "(");
            if (!miembro && tipo.nombre.equals("void")) continue;
            String categoria = metodo ? "método" : miembro ? "campo" : "variable";
            u.ambito.simbolos.add(new Simbolo(nombre.texto(), categoria + " · " + tipo.nombre, nombre.fin, miembro));
        }

        Ambito visible = global;
        for (Ambito a : ambitos) if (a.inicio <= cursor && cursor <= a.fin && profundidad(a) > profundidad(visible)) visible = a;
        Set<String> nombres = new HashSet<>();
        for (Ambito a = visible; a != null; a = a.padre) {
            for (Simbolo s : a.simbolos) if ((s.adelantado || s.posicion <= inicio) && nombres.add(s.sugerencia.texto)) {
                if (s.sugerencia.texto.startsWith(prefijo) && !s.sugerencia.texto.equals(palabra)) salida.add(s.sugerencia);
            }
        }
        for (String clave : Lexico.palabrasClave()) if (!nombres.contains(clave) && clave.startsWith(prefijo) && !clave.equals(palabra)) {
            salida.add(new Sugerencia(clave, "palabra reservada", false));
        }
        salida.sort(Comparator.comparing((Sugerencia s) -> !s.simbolo).thenComparing(s -> s.texto, String.CASE_INSENSITIVE_ORDER));
        return new Resultado(inicio, fin, cursor, salida);
    }

    private static boolean limiteDeclaracion(List<Unidad> tokens, int i) {
        return i == 0 || es(tokens, i - 1, "{") || es(tokens, i - 1, "}") || es(tokens, i - 1, ";")
                || es(tokens, i - 1, "public") || es(tokens, i - 1, "private");
    }
    private static void agregarParametros(List<Unidad> tokens, int inicio, int fin, Ambito destino, boolean metodo) {
        for (int i = inicio; i < fin;) {
            TipoLeido tipo = leerTipo(tokens, i);
            if (tipo != null && tipo.siguiente < fin && tokens.get(tipo.siguiente).id()) {
                Unidad nombre = tokens.get(tipo.siguiente);
                destino.simbolos.add(new Simbolo(nombre.texto(), (metodo ? "parámetro" : "iterador") + " · " + tipo.nombre, nombre.fin, false));
                i = tipo.siguiente + 1;
                if (!metodo) break;
                if (es(tokens, i, ",")) i++;
                else break;
            } else i++;
        }
    }
    private static TipoLeido leerTipo(List<Unidad> tokens, int i) {
        if (i >= tokens.size()) return null;
        Unidad primero = tokens.get(i);
        if (!primero.id() && !primero.texto().matches("int|float|string|bool|list|void|var|let")) return null;
        StringBuilder tipo = new StringBuilder(primero.texto());
        int siguiente = i + 1;
        if (primero.texto().equals("list") && es(tokens, siguiente, "<")) {
            int profundidad = 0;
            do {
                String s = tokens.get(siguiente).texto();
                if (!s.matches("[A-Za-z][A-Za-z0-9]*|<|>")) return null;
                if (s.equals("<")) profundidad++;
                if (s.equals(">")) profundidad--;
                tipo.append(s);
                siguiente++;
            } while (siguiente < tokens.size() && profundidad > 0);
            if (profundidad != 0) return null;
        }
        return new TipoLeido(tipo.toString(), siguiente);
    }
    private static int profundidad(Ambito a) { int n = 0; for (; a != null; a = a.padre) n++; return n; }
    private static boolean es(List<Unidad> tokens, int i, String texto) { return i >= 0 && i < tokens.size() && tokens.get(i).texto().equals(texto); }
    private static Map<Integer, Integer> emparejar(List<Unidad> tokens, String apertura, String cierre) {
        Map<Integer, Integer> pares = new HashMap<>();
        Deque<Integer> pila = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (es(tokens, i, apertura)) pila.push(i);
            else if (es(tokens, i, cierre) && !pila.isEmpty()) pares.put(i, pila.pop());
        }
        return pares;
    }
    private static List<Unidad> tokenizar(String fuente) {
        Lexico lexico = new Lexico(fuente);
        lexico.tokenizar();
        List<Integer> lineas = new ArrayList<>();
        lineas.add(0);
        for (int i = 0; i < fuente.length(); i++) if (fuente.charAt(i) == '\n') lineas.add(i + 1);
        List<Unidad> resultado = new ArrayList<>();
        for (Token t : lexico.getTokens()) if (t.tipo != tipoToken.EOF && t.linea <= lineas.size()) {
            resultado.add(new Unidad(t, lineas.get(t.linea - 1) + t.columna - 1));
        }
        return resultado;
    }
    static boolean enLiteralOComentario(String fuente, int cursor) {
        boolean cadena = false, comentario = false;
        for (int i = 0; i < cursor; i++) {
            char c = fuente.charAt(i);
            if (c == '\n') { cadena = false; comentario = false; }
            else if (!comentario && c == '"') cadena = !cadena;
            else if (!cadena && !comentario && c == '/' && i + 1 < cursor && fuente.charAt(i + 1) == '/') { comentario = true; i++; }
        }
        return cadena || comentario;
    }
    private static boolean letra(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static boolean identificador(char c) { return letra(c) || c >= '0' && c <= '9'; }
}
