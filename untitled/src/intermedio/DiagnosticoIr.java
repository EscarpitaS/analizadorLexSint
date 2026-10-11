import java.util.Objects;

final class DiagnosticoIr {
    final String codigo, mensaje, funcion, bloque;
    final int instruccion;
    final OrigenIr origen;
    DiagnosticoIr(String codigo, String mensaje, String funcion, String bloque, int instruccion, OrigenIr origen) {
        this.codigo = Objects.requireNonNull(codigo); this.mensaje = Objects.requireNonNull(mensaje);
        this.funcion = funcion; this.bloque = bloque; this.instruccion = instruccion; this.origen = origen;
    }
    @Override public String toString() {
        return codigo + ": " + mensaje + (funcion == null ? "" : " [" + funcion
                + (bloque == null ? "" : "/" + bloque + "/" + instruccion) + "]") + " @ " + origen;
    }
}
