/** Fallo del programa con código estable y ubicación fuente. */
final class ErrorEjecucionIr extends RuntimeException {
    final String codigo;
    final OrigenIr origen;
    String diagnostico() {
        return "Error de ejecución [" + codigo + "] " + ImpresorIr.escapar(origen.archivo) + ":" + origen.linea + ":" + origen.columna + ": " + codigo;
    }
    ErrorEjecucionIr(String codigo, OrigenIr origen) { super(codigo + " en " + origen); this.codigo = codigo; this.origen = origen; }
}
