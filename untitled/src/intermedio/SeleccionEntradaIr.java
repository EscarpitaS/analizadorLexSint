import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class SeleccionEntradaIr {
    private SeleccionEntradaIr() { }
    static boolean esCandidata(FirmaIr f) {
        return f.nombre.equals("main") && f.visibilidad == FirmaIr.Visibilidad.PUBLICA
                && f.retorno.equals(TipoIr.VOID) && f.parametros.isEmpty();
    }
    /** Una sola candidata se elige automáticamente; varias requieren clase explícita. */
    static FirmaIr elegir(List<FirmaIr> firmas, String clase) {
        if (clase != null) IdentificadoresIr.nombre(clase);
        List<FirmaIr> candidatas = new ArrayList<>();
        for (FirmaIr f : firmas) if (esCandidata(Objects.requireNonNull(f))
                && (clase == null || f.propietaria.equals(clase))) candidatas.add(f);
        if (candidatas.size() != 1) throw new IllegalArgumentException(candidatas.isEmpty()
                ? "No existe main público declarado en la clase de entrada" : "Hay varios main: indica la clase de entrada");
        return candidatas.get(0);
    }
}
