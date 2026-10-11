import java.util.*;

/** Proyección física cerrada: el backend recibe este módulo, no AST ni ámbitos. */
final class ModuloAtomicoIr {
    final String nombre;
    final FirmaIr entrada;
    final LayoutProgramaIr layout;
    final List<FuncionAtomicaIr> funciones;
    ModuloAtomicoIr(String nombre, FirmaIr entrada, LayoutProgramaIr layout, List<FuncionAtomicaIr> funciones) {
        this.nombre = IdentificadoresIr.nombre(nombre); this.entrada = entrada; this.layout = Objects.requireNonNull(layout);
        this.funciones = Collections.unmodifiableList(new ArrayList<>(funciones));
    }
}
final class FuncionAtomicaIr {
    final FirmaIr firma;
    final ValorIr receptor;
    final List<ValorIr> parametros;
    final List<CuadruploIr> codigo;
    FuncionAtomicaIr(FirmaIr firma, ValorIr receptor, List<ValorIr> parametros, List<CuadruploIr> codigo) {
        this.firma = Objects.requireNonNull(firma); this.receptor = Objects.requireNonNull(receptor);
        this.parametros = Collections.unmodifiableList(new ArrayList<>(parametros)); this.codigo = Collections.unmodifiableList(new ArrayList<>(codigo));
    }
}
