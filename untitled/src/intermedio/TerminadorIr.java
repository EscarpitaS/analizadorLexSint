import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class TerminadorIr {
    enum Clase { SALTO, CONDICIONAL, RETORNO }
    final Clase clase;
    final ValorIr valor;
    final List<String> destinos;
    final OrigenIr origen;
    private TerminadorIr(Clase clase, ValorIr valor, List<String> destinos, OrigenIr origen) {
        this.clase = clase; this.valor = valor; this.destinos = Collections.unmodifiableList(destinos); this.origen = Objects.requireNonNull(origen);
        for (String destino : destinos) IdentificadoresIr.nombre(destino);
    }
    static TerminadorIr salto(String destino, OrigenIr origen) { return new TerminadorIr(Clase.SALTO, null, Arrays.asList(destino), origen); }
    static TerminadorIr condicional(ValorIr valor, String si, String no, OrigenIr origen) {
        return new TerminadorIr(Clase.CONDICIONAL, Objects.requireNonNull(valor), Arrays.asList(si, no), origen);
    }
    static TerminadorIr retorno(ValorIr valor, OrigenIr origen) { return new TerminadorIr(Clase.RETORNO, valor, Collections.emptyList(), origen); }
}
