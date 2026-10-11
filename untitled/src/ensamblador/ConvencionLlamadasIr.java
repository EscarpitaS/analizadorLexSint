import java.util.*;

/** System V AMD64 para valores escalares: GPR y SSE se asignan por separado. */
final class ConvencionLlamadasIr {
    // Volátil, fuera de los bancos de argumentos; se recarga justo antes de CALL_INDIRECT.
    static final String REGISTRO_DESTINO_INDIRECTO = "r11";
    private static final String[] ENTEROS = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};
    static List<LugarArgumentoIr> argumentos(List<TipoIr> tipos, boolean entrantes) {
        List<LugarArgumentoIr> salida = new ArrayList<>(); int gpr = 0, sse = 0, pila = 0;
        for (int i = 0; i < tipos.size(); i++) {
            TipoIr tipo = tipos.get(i); String registro = null;
            if (tipo.equals(TipoIr.FLOAT)) { if (sse < 8) registro = "xmm" + sse++; }
            else if (gpr < ENTEROS.length) registro = ENTEROS[gpr++];
            int offset = registro == null ? (entrantes ? 16 : 0) + 8 * pila++ : 0;
            salida.add(new LugarArgumentoIr(i, tipo, registro, offset, entrantes));
        }
        return Collections.unmodifiableList(salida);
    }
    static String retorno(TipoIr tipo) { return tipo.equals(TipoIr.VOID) ? "ninguno" : tipo.equals(TipoIr.FLOAT) ? "xmm0" : "rax"; }
}
final class LugarArgumentoIr {
    final int indice, offset;
    final TipoIr tipo;
    final String registro;
    final boolean entrante;
    LugarArgumentoIr(int indice, TipoIr tipo, String registro, int offset, boolean entrante) {
        this.indice = indice; this.tipo = Objects.requireNonNull(tipo); this.registro = registro; this.offset = offset; this.entrante = entrante;
    }
    @Override public boolean equals(Object otro) {
        if (!(otro instanceof LugarArgumentoIr)) return false;
        LugarArgumentoIr o = (LugarArgumentoIr) otro;
        return indice == o.indice && offset == o.offset && tipo.equals(o.tipo) && Objects.equals(registro, o.registro) && entrante == o.entrante;
    }
    @Override public int hashCode() { return Objects.hash(indice, offset, tipo, registro, entrante); }
    @Override public String toString() { return registro != null ? registro : "[" + (entrante ? "rbp+" : "rsp+") + offset + "]"; }
}
