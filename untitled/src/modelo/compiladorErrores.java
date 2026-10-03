class compiladorErrores {
    public enum TIPO_ERROR { LEXICAL, SINTACTICO, SEMANTICO }
    public final TIPO_ERROR TIPOERROR;
    public final int linea;
    public final int columna;
    public final String lexema;
    public final String mensaje;
    public final String pista;

    public compiladorErrores(TIPO_ERROR TIPOERROR, int linea, int columna,
                             String lexema, String mensaje, String pista) {
        this.TIPOERROR = TIPOERROR;
        this.linea = linea;
        this.columna = columna;
        this.lexema = lexema;
        this.mensaje = mensaje;
        this.pista = pista;
    }

    @Override
    public String toString() {
        String base = String.format("[%s] Linea %d, col %d -- %s '%s'",
                TIPOERROR, linea, columna, mensaje, lexema);
        return pista != null ? base + "\n         Sugerencia: " + pista : base;
    }
}
