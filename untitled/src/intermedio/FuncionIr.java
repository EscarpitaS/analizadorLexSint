import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class FuncionIr {
    final FirmaIr firma;
    final ValorIr receptor;
    final List<ValorIr> parametros;
    final List<SimboloIr> locales;
    final List<BloqueIr> bloques;
    FuncionIr(FirmaIr firma, ValorIr receptor, List<ValorIr> parametros, List<SimboloIr> locales, List<BloqueIr> bloques) {
        this.firma = Objects.requireNonNull(firma); this.receptor = Objects.requireNonNull(receptor);
        this.parametros = copia(parametros); this.locales = copia(locales); this.bloques = copia(bloques);
    }
    private static <T> List<T> copia(List<T> lista) {
        List<T> copia = new ArrayList<>(lista); for (T elemento : copia) Objects.requireNonNull(elemento);
        return Collections.unmodifiableList(copia);
    }
}
