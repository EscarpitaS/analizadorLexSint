import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Protocolo binario del harness Python: no forma parte de la API del lenguaje. */
public final class SondaConsola {
    private static String leerTexto(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n == -1) return null;
        byte[] b = new byte[n]; in.readFully(b); return new String(b, StandardCharsets.UTF_8);
    }
    private static void escribirTexto(DataOutputStream out, String s) throws IOException {
        if (s == null) { out.writeInt(-1); return; }
        byte[] b = s.getBytes(StandardCharsets.UTF_8); out.writeInt(b.length); out.write(b);
    }
    public static void main(String[] args) throws IOException {
        DataInputStream in = new DataInputStream(System.in); DataOutputStream out = new DataOutputStream(System.out);
        int casos = in.readInt();
        for (int caso = 0; caso < casos; caso++) {
            int n = in.readInt(); byte[] bytes = new byte[n]; in.readFully(bytes);
            String inject = leerTexto(in); int indiceFallo = in.readInt(), trasBytes = in.readInt();
            int operaciones = in.readInt();
            String[] metodos = new String[operaciones], tipos = new String[operaciones], argumentos = new String[operaciones];
            for (int i = 0; i < operaciones; i++) { metodos[i] = leerTexto(in); tipos[i] = leerTexto(in); argumentos[i] = leerTexto(in); }
            final int[] opActual = {0};
            InputStream entrada = new InputStream() {
                int posicion;
                @Override public int read() throws IOException {
                    if ("read_error".equals(inject) && opActual[0] == indiceFallo && posicion >= trasBytes) throw new IOException("inyectado");
                    return posicion == bytes.length ? -1 : bytes[posicion++] & 255;
                }
            };
            ByteArrayOutputStream capturado = new ByteArrayOutputStream();
            OutputStream salida = new OutputStream() {
                @Override public void write(int b) throws IOException {
                    if ("write_error".equals(inject) && opActual[0] == indiceFallo && capturado.size() >= trasBytes) throw new IOException("inyectado");
                    capturado.write(b);
                }
                @Override public void flush() throws IOException {
                    if ("flush_error".equals(inject) && opActual[0] == indiceFallo) throw new IOException("inyectado");
                }
            };
            ConsolaReferencia consola = new ConsolaReferencia(entrada, salida, (longitud, origen) -> {
                if ("allocation_error".equals(inject) && opActual[0] == indiceFallo) throw new ErrorEjecucionIr("MEMORIA_AGOTADA", origen);
            });
            List<Integer> indices = new ArrayList<>(); List<Object> valores = new ArrayList<>();
            String error = null; int operacionError = -1;
            for (int i = 0; i < operaciones; i++) {
                opActual[0] = i; RuntimeConsolaId id = RuntimeConsolaId.desdeNombre(metodos[i]);
                Object valor = argumentos[i];
                if ("int".equals(tipos[i])) valor = Long.valueOf(argumentos[i]);
                if ("float".equals(tipos[i])) valor = Double.valueOf(argumentos[i]);
                if ("bool".equals(tipos[i])) valor = Boolean.valueOf(argumentos[i]);
                if ("list<int>".equals(tipos[i])) {
                    String[] numeros = argumentos[i].isEmpty() ? new String[0] : argumentos[i].split(",");
                    MemoriaReferenciaIr.Lista lista = new MemoriaReferenciaIr().lista(TipoIr.INT, numeros.length, OrigenIr.GENERADO);
                    for (int j = 0; j < numeros.length; j++) lista.elementos[j].guardar(Long.valueOf(numeros[j]));
                    valor = lista;
                }
                if ("objeto".equals(tipos[i]) || "Consola".equals(tipos[i])) valor = new InterpreteIr.Referencia(argumentos[i]);
                try {
                    Object retorno = consola.llamar(id, id.imprime() ? (tipos[i] == null ? "" : ConsolaReferencia.texto(valor)) : null, new OrigenIr("oraculo", 1, i + 1));
                    if (!id.imprime()) { indices.add(i); valores.add(retorno); }
                } catch (ErrorEjecucionIr e) { error = e.codigo; operacionError = i; break; }
            }
            byte[] escrito = capturado.toByteArray(); out.writeInt(escrito.length); out.write(escrito);
            escribirTexto(out, error); out.writeInt(operacionError); out.writeInt(valores.size());
            for (int i = 0; i < valores.size(); i++) {
                out.writeInt(indices.get(i)); Object valor = valores.get(i);
                escribirTexto(out, valor == null ? null : ConsolaReferencia.texto(valor));
                out.writeLong(valor instanceof Double ? Double.doubleToRawLongBits((Double) valor) : 0);
            }
        }
        out.flush();
    }
}
