import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Declaraciones y firma del inicializador; los offsets se calculan al linealizar. */
final class ClaseIr {
    final String nombre, base;
    final boolean consolaIntegrada;
    final List<SimboloIr> campos;
    final FirmaIr inicializador;
    ClaseIr(String nombre, String base, List<SimboloIr> campos) {
        this(nombre, base, campos, false);
    }
    ClaseIr(String nombre, String base, List<SimboloIr> campos, boolean consolaIntegrada) {
        this(nombre, base, campos, consolaIntegrada, null);
    }
    ClaseIr(String nombre, String base, List<SimboloIr> campos, boolean consolaIntegrada, FirmaIr inicializador) {
        this.inicializador = inicializador;
        this.consolaIntegrada = consolaIntegrada;
        this.nombre = IdentificadoresIr.nombre(nombre); this.base = base == null ? null : IdentificadoresIr.nombre(base);
        this.campos = Collections.unmodifiableList(new ArrayList<>(campos));
        for (SimboloIr campo : this.campos) Objects.requireNonNull(campo);
    }
}
