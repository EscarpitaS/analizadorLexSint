/** Catálogo cerrado; los nombres del usuario nunca seleccionan el runtime por sí solos. */
enum RuntimeConsolaId {
    CONSOLA_IMPRIMIR("imprimir", TipoIr.VOID), CONSOLA_IMPRIMIR_LINEA("imprimirLinea", TipoIr.VOID),
    CONSOLA_LEER_INT("leerInt", TipoIr.INT), CONSOLA_LEER_FLOAT("leerFloat", TipoIr.FLOAT),
    CONSOLA_LEER_BOOL("leerBool", TipoIr.BOOL), CONSOLA_LEER_LINEA("leerLinea", TipoIr.STRING);
    final String nombre;
    final TipoIr retorno;
    RuntimeConsolaId(String nombre, TipoIr retorno) { this.nombre = nombre; this.retorno = retorno; }
    boolean imprime() { return retorno.equals(TipoIr.VOID); }
    Semantico.Tipo retornoSemantico() {
        if (retorno.equals(TipoIr.INT)) return Semantico.INT;
        if (retorno.equals(TipoIr.FLOAT)) return Semantico.FLOAT;
        if (retorno.equals(TipoIr.BOOL)) return Semantico.BOOL;
        return imprime() ? Semantico.VOID : Semantico.STRING;
    }
    static RuntimeConsolaId desdeNombre(String nombre) {
        for (RuntimeConsolaId id : values()) if (id.nombre.equals(nombre)) return id;
        return null;
    }
}
