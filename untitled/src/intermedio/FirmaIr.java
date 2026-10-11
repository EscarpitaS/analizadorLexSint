import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** El retorno no distingue sobrecargas. Todas las funciones del lenguaje tienen receptor. */
final class FirmaIr {
    enum Visibilidad { PUBLICA, PRIVADA }
    final String propietaria, nombre;
    final List<TipoIr> parametros;
    final TipoIr retorno;
    final Visibilidad visibilidad;
    FirmaIr(String propietaria, String nombre, TipoIr retorno, Visibilidad visibilidad, List<TipoIr> parametros) {
        this.propietaria = IdentificadoresIr.nombre(propietaria); this.nombre = IdentificadoresIr.nombre(nombre);
        this.retorno = Objects.requireNonNull(retorno); this.visibilidad = Objects.requireNonNull(visibilidad);
        this.parametros = Collections.unmodifiableList(new ArrayList<>(parametros));
        if (!retorno.equals(TipoIr.VOID) && !retorno.esAlmacenable()) throw new IllegalArgumentException("Retorno inválido");
        for (TipoIr tipo : this.parametros) if (!tipo.esAlmacenable()) throw new IllegalArgumentException("Parámetro inválido");
    }
    String id() { return propietaria + "::" + nombre + "(" + String.join(",", parametros.stream().map(Object::toString).toArray(String[]::new)) + ")"; }
    boolean mismoContrato(FirmaIr otra) {
        return id().equals(otra.id()) && retorno.equals(otra.retorno) && visibilidad == otra.visibilidad;
    }
}
