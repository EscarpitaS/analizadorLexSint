import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import runtime.jdk11.FloatingDecimal;
import java.util.*;

/** Adaptador RC-1 del intérprete. Canales compartidos e inyectables, nunca cerrados. */
final class ConsolaReferencia {
    static final int MAX_LINEA = 1024 * 1024;
    private final InputStream entrada;
    private final OutputStream salida;
    private final java.util.function.BiConsumer<Integer, OrigenIr> reservaString;
    private boolean eof;
    ConsolaReferencia(InputStream entrada, OutputStream salida) { this(entrada, salida, (bytes, origen) -> { }); }
    ConsolaReferencia(InputStream entrada, OutputStream salida, java.util.function.BiConsumer<Integer, OrigenIr> reservaString) {
        this.entrada = entrada; this.salida = salida; this.reservaString = Objects.requireNonNull(reservaString);
    }
    Object llamar(RuntimeConsolaId id, String argumento, OrigenIr origen) {
        try { return llamarInterno(id, argumento, origen); }
        catch (OutOfMemoryError e) { throw new ErrorEjecucionIr("MEMORIA_AGOTADA", origen); }
    }
    private Object llamarInterno(RuntimeConsolaId id, String argumento, OrigenIr origen) {
        if (id.imprime()) {
            try {
                salida.write(argumento.getBytes(StandardCharsets.UTF_8));
                if (id == RuntimeConsolaId.CONSOLA_IMPRIMIR_LINEA) salida.write(10);
                salida.flush();
            } catch (IOException e) { throw new ErrorEjecucionIr("ERROR_ESCRITURA_CONSOLA", origen); }
            return null;
        }
        String linea = leerLinea(origen);
        if (id == RuntimeConsolaId.CONSOLA_LEER_LINEA) return linea;
        if (linea == null) throw new ErrorEjecucionIr("FIN_DE_ENTRADA", origen);
        String texto = recortar(linea);
        switch (id) {
            case CONSOLA_LEER_INT:
                if (!texto.matches("[+-]?[0-9]+")) throw new ErrorEjecucionIr("ENTRADA_INVALIDA", origen);
                try { return Long.parseLong(texto); }
                catch (NumberFormatException e) { throw new ErrorEjecucionIr("ENTRADA_FUERA_DE_RANGO", origen); }
            case CONSOLA_LEER_FLOAT:
                if (!texto.matches("[+-]?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new ErrorEjecucionIr("ENTRADA_INVALIDA", origen);
                double n = Double.parseDouble(texto);
                if (!Double.isFinite(n)) throw new ErrorEjecucionIr("ENTRADA_FUERA_DE_RANGO", origen);
                return n;
            case CONSOLA_LEER_BOOL:
                if (!texto.equals("true") && !texto.equals("false")) throw new ErrorEjecucionIr("ENTRADA_INVALIDA", origen);
                return Boolean.valueOf(texto);
            default: throw new AssertionError(id);
        }
    }
    String leerLinea(OrigenIr origen) {
        if (eof) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean lf = false;
        try {
            while (true) {
                int b = entrada.read();
                if (b == -1) { eof = true; break; }
                if (b == 10) { lf = true; break; }
                bytes.write(b);
                // Se permite un byte extra solo mientras pueda ser el CR de CRLF.
                if (bytes.size() > MAX_LINEA + 1) throw new ErrorEjecucionIr("LINEA_DEMASIADO_LARGA", origen);
            }
        } catch (IOException e) { throw new ErrorEjecucionIr("ERROR_LECTURA_CONSOLA", origen); }
        byte[] datos = bytes.toByteArray();
        int longitud = datos.length;
        if (lf && longitud > 0 && datos[longitud - 1] == 13) longitud--;
        if (longitud > MAX_LINEA) throw new ErrorEjecucionIr("LINEA_DEMASIADO_LARGA", origen);
        if (!lf && longitud == 0 && eof) return null;
        try {
            String linea = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(datos, 0, longitud)).toString();
            reservaString.accept(longitud, origen);
            return linea;
        } catch (CharacterCodingException e) { throw new ErrorEjecucionIr("UTF8_INVALIDO", origen); }
    }
    private static boolean espacio(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\u000b' || c == '\f'; }
    private static String recortar(String s) {
        int inicio = 0, fin = s.length();
        while (inicio < fin && espacio(s.charAt(inicio))) inicio++;
        while (fin > inicio && espacio(s.charAt(fin - 1))) fin--;
        return s.substring(inicio, fin);
    }
    private enum Cierre { CORCHETE, COMA, NULO }
    static String texto(Object valor) {
        if (valor == null) return "null";
        if (valor instanceof Double) return FloatingDecimal.toJavaFormatString((Double) valor);
        if (valor instanceof InterpreteIr.Referencia) return "<" + ((InterpreteIr.Referencia) valor).clase + ">";
        if (valor instanceof MemoriaReferenciaIr.Lista) {
            StringBuilder s = new StringBuilder();
            Deque<Object> pendientes = new ArrayDeque<>(); pendientes.push(valor);
            while (!pendientes.isEmpty()) {
                Object v = pendientes.pop();
                if (v instanceof MemoriaReferenciaIr.Lista) {
                    MemoriaReferenciaIr.Celda[] xs = ((MemoriaReferenciaIr.Lista) v).elementos;
                    s.append('['); pendientes.push(Cierre.CORCHETE);
                    for (int i = xs.length - 1; i >= 0; i--) {
                        if (i < xs.length - 1) pendientes.push(Cierre.COMA);
                        pendientes.push(xs[i].valor == null ? Cierre.NULO : xs[i].valor);
                    }
                } else if (v == Cierre.CORCHETE) s.append(']');
                else if (v == Cierre.COMA) s.append(", ");
                else s.append(v == Cierre.NULO ? "null" : texto(v));
            }
            return s.toString();
        }
        return valor.toString();
    }
}
