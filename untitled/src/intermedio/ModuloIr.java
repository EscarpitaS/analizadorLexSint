import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Módulo inmutable; entrada null permite módulos de biblioteca o ejemplos parciales. */
final class ModuloIr {
    final String nombre;
    final List<ClaseIr> clases;
    final List<FuncionIr> funciones;
    final FirmaIr entrada;
    ModuloIr(String nombre, List<ClaseIr> clases, List<FuncionIr> funciones, FirmaIr entrada) {
        this.nombre = IdentificadoresIr.nombre(nombre);
        this.clases = copia(clases); this.funciones = copia(funciones); this.entrada = entrada;
    }
    private static <T> List<T> copia(List<T> lista) {
        List<T> copia = new ArrayList<>(lista); for (T elemento : copia) Objects.requireNonNull(elemento);
        return Collections.unmodifiableList(copia);
    }
}
