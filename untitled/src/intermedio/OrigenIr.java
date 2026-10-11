import java.util.Objects;

/** Coordenadas del código fuente o marca explícita de instrucción generada. */
final class OrigenIr {
    static final OrigenIr GENERADO = new OrigenIr("<generado>", 0, 0, true);
    final String archivo;
    final int linea, columna;
    final boolean generado;
    OrigenIr(String archivo, int linea, int columna) { this(archivo, linea, columna, false); }
    private OrigenIr(String archivo, int linea, int columna, boolean generado) {
        if (!generado && (linea < 1 || columna < 1)) throw new IllegalArgumentException("Ubicación inválida");
        this.archivo = Objects.requireNonNull(archivo); this.linea = linea; this.columna = columna; this.generado = generado;
    }
    @Override public String toString() { return generado ? archivo : archivo + ":" + linea + ":" + columna; }
}
