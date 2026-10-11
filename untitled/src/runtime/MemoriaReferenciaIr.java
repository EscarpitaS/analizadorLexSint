import java.util.*;

/** Heap de referencia: alias por identidad y celdas compartidas entre accesos. */
final class MemoriaReferenciaIr {
    static final class Celda {
        Object valor;
        boolean inicializada;
        Celda() { }
        Celda(Object valor) { this.valor = valor; inicializada = true; }
        Object leer(OrigenIr origen) {
            if (!inicializada) throw new IllegalArgumentException("IR lee una local sin inicializar en " + origen);
            return valor;
        }
        void guardar(Object valor) { this.valor = valor; inicializada = true; }
    }
    static final class Lista {
        final TipoIr elemento;
        final Celda[] elementos;
        Lista(TipoIr elemento, int longitud) {
            this.elemento = elemento; elementos = new Celda[longitud];
            for (int i = 0; i < longitud; i++) elementos[i] = new Celda(predeterminado(elemento));
        }
    }
    final long maxBytes;
    long bytes;
    MemoriaReferenciaIr() { this(Long.MAX_VALUE); }
    MemoriaReferenciaIr(long maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("Presupuesto de memoria negativo");
        this.maxBytes = maxBytes;
    }
    void reservar(long cantidad, OrigenIr o) {
        if (cantidad < 0 || cantidad > maxBytes - bytes) throw new ErrorEjecucionIr("MEMORIA_AGOTADA", o);
        bytes += cantidad;
    }
    InterpreteIr.Referencia objeto(String clase, Map<String, TipoIr> campos, OrigenIr o) {
        reservar(16L + 8L * campos.size(), o);
        try {
            InterpreteIr.Referencia r = new InterpreteIr.Referencia(clase);
            for (Map.Entry<String, TipoIr> c : campos.entrySet()) r.campos.put(c.getKey(), new Celda(predeterminado(c.getValue())));
            return r;
        } catch (OutOfMemoryError e) { throw new ErrorEjecucionIr("MEMORIA_AGOTADA", o); }
    }
    Lista lista(TipoIr elemento, long longitud, OrigenIr o) {
        // La implementación Java del harness usa arrays con índice int; el contrato nativo conserva int64.
        if (longitud < 0 || longitud > Integer.MAX_VALUE - 8) throw new ErrorEjecucionIr("MEMORIA_AGOTADA", o);
        reservar(16L + 8L * longitud, o);
        try { return new Lista(elemento, (int) longitud); }
        catch (OutOfMemoryError e) { throw new ErrorEjecucionIr("MEMORIA_AGOTADA", o); }
    }
    static Object predeterminado(TipoIr t) {
        if (t.equals(TipoIr.INT)) return 0L;
        if (t.equals(TipoIr.FLOAT)) return 0.0;
        if (t.equals(TipoIr.BOOL)) return false;
        return null;
    }
    static void noNulo(Object v, OrigenIr o) { if (v == null) throw new ErrorEjecucionIr("REFERENCIA_NULA", o); }
    static Lista comprobarIndice(Object v, long indice, OrigenIr o) {
        noNulo(v, o); Lista lista = (Lista) v;
        if (indice < 0 || indice >= lista.elementos.length) throw new ErrorEjecucionIr("INDICE_FUERA_DE_RANGO", o);
        return lista;
    }
}
