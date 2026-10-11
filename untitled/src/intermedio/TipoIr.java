import java.util.Objects;

/** Tipos de valores y direcciones de la IR; no dependen del layout nativo. */
final class TipoIr {
    enum Categoria { INT, FLOAT, BOOL, STRING, OBJETO, LISTA, NULO, VOID, DIRECCION }
    static final TipoIr INT = new TipoIr(Categoria.INT, null, null);
    static final TipoIr FLOAT = new TipoIr(Categoria.FLOAT, null, null);
    static final TipoIr BOOL = new TipoIr(Categoria.BOOL, null, null);
    static final TipoIr STRING = new TipoIr(Categoria.STRING, null, null);
    static final TipoIr NULO = new TipoIr(Categoria.NULO, null, null);
    static final TipoIr VOID = new TipoIr(Categoria.VOID, null, null);
    final Categoria categoria;
    final String clase;
    final TipoIr elemento;
    private TipoIr(Categoria categoria, String clase, TipoIr elemento) {
        this.categoria = categoria; this.clase = clase; this.elemento = elemento;
    }
    static TipoIr objeto(String clase) { return new TipoIr(Categoria.OBJETO, IdentificadoresIr.nombre(clase), null); }
    static TipoIr lista(TipoIr elemento) {
        if (!Objects.requireNonNull(elemento).esAlmacenable()) throw new IllegalArgumentException("Elemento de lista inválido");
        return new TipoIr(Categoria.LISTA, null, elemento);
    }
    static TipoIr direccion(TipoIr elemento) {
        if (!Objects.requireNonNull(elemento).esAlmacenable()) throw new IllegalArgumentException("Tipo de almacenamiento inválido");
        return new TipoIr(Categoria.DIRECCION, null, elemento);
    }
    boolean esReferencia() { return categoria == Categoria.STRING || categoria == Categoria.OBJETO || categoria == Categoria.LISTA; }
    boolean esNumerico() { return equals(INT) || equals(FLOAT); }
    boolean esAlmacenable() { return categoria != Categoria.VOID && categoria != Categoria.NULO && categoria != Categoria.DIRECCION; }
    static TipoIr desdeSemantico(Semantico.Tipo tipo) {
        switch (Objects.requireNonNull(tipo).categoria) {
            case INT: return INT;
            case FLOAT: return FLOAT;
            case BOOL: return BOOL;
            case STRING: return STRING;
            case VOID: return VOID;
            case NULL: return NULO;
            case CLASE: return objeto(tipo.nombreClase);
            case LISTA: return lista(desdeSemantico(tipo.elemento));
            default: throw new IllegalArgumentException("Tipo semántico incompleto: " + tipo);
        }
    }
    @Override public boolean equals(Object otro) {
        if (!(otro instanceof TipoIr)) return false;
        TipoIr t = (TipoIr) otro;
        return categoria == t.categoria && Objects.equals(clase, t.clase) && Objects.equals(elemento, t.elemento);
    }
    @Override public int hashCode() { return Objects.hash(categoria, clase, elemento); }
    @Override public String toString() {
        if (categoria == Categoria.NULO) return "null";
        if (categoria == Categoria.OBJETO) return clase;
        if (categoria == Categoria.LISTA) return "list<" + elemento + ">";
        if (categoria == Categoria.DIRECCION) return "dir<" + elemento + ">";
        return categoria.name().toLowerCase(java.util.Locale.ROOT);
    }
}

final class IdentificadoresIr {
    private IdentificadoresIr() { }
    static String nombre(String nombre) {
        if (nombre == null || !nombre.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("Identificador IR inválido: " + nombre);
        return nombre;
    }
}
