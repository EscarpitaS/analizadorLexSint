class Token {
    public final tipoToken tipo;
    public final String lexema;
    public final int linea;
    public final int columna;

    public Token(tipoToken tipo, String lexema, int linea, int columna) {
        this.tipo = tipo;
        this.lexema = lexema;
        this.linea = linea;
        this.columna = columna;
    }

    @Override
    public String toString() {
        return String.format("[%-12s] %-24s (linea %d, col %d)",
                tipo, '"' + lexema + '"', linea, columna);
    }
}
