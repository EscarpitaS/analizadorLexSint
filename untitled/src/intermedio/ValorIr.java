import java.util.Objects;

/** Un valor no es una ranura de almacenamiento; direcciones son valores tipados. */
final class ValorIr {
    enum Clase { CONSTANTE, PARAMETRO, TEMPORAL }
    final Clase clase;
    final TipoIr tipo;
    final String id;
    final Object literal;
    private ValorIr(Clase clase, TipoIr tipo, String id, Object literal) {
        this.clase = clase; this.tipo = Objects.requireNonNull(tipo); this.id = id; this.literal = literal;
        if (tipo.equals(TipoIr.VOID)) throw new IllegalArgumentException("void no es un valor");
    }
    static ValorIr temporal(String id, TipoIr tipo) { return new ValorIr(Clase.TEMPORAL, tipo, IdentificadoresIr.nombre(id), null); }
    static ValorIr parametro(String id, TipoIr tipo) {
        if (!tipo.esAlmacenable()) throw new IllegalArgumentException("Parámetro inválido");
        return new ValorIr(Clase.PARAMETRO, tipo, IdentificadoresIr.nombre(id), null);
    }
    static ValorIr entero(long n) { return new ValorIr(Clase.CONSTANTE, TipoIr.INT, null, n); }
    static ValorIr flotante(double n) { return new ValorIr(Clase.CONSTANTE, TipoIr.FLOAT, null, n); }
    static ValorIr booleano(boolean b) { return new ValorIr(Clase.CONSTANTE, TipoIr.BOOL, null, b); }
    static ValorIr cadena(String s) { return new ValorIr(Clase.CONSTANTE, TipoIr.STRING, null, Objects.requireNonNull(s)); }
    static ValorIr nulo() { return new ValorIr(Clase.CONSTANTE, TipoIr.NULO, null, null); }
}
