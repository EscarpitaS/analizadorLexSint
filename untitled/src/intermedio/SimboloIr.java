import java.util.Objects;

/** Identidad de almacenamiento estable; dos locales homónimas conservan IDs distintos. */
final class SimboloIr {
    enum Rol { LOCAL, CAMPO }
    final String id, nombre, propietaria;
    final TipoIr tipo;
    final Rol rol;
    final OrigenIr origen;
    SimboloIr(String id, String nombre, TipoIr tipo, Rol rol, String propietaria, OrigenIr origen) {
        this.id = IdentificadoresIr.nombre(id); this.nombre = IdentificadoresIr.nombre(nombre);
        this.tipo = Objects.requireNonNull(tipo); this.rol = Objects.requireNonNull(rol);
        this.propietaria = IdentificadoresIr.nombre(propietaria); this.origen = Objects.requireNonNull(origen);
        if (!tipo.esAlmacenable()) throw new IllegalArgumentException("Tipo no almacenable: " + tipo);
    }
}
