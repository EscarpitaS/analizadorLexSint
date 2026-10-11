import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Barrera de entrada para H2: consume resultados finales del coordinador de proyecto. */
final class EntradaProyectoIr {
    final List<ResultadoSemantico> unidades;
    private EntradaProyectoIr(List<ResultadoSemantico> unidades) {
        this.unidades = Collections.unmodifiableList(unidades);
    }
    static EntradaProyectoIr desdeResultados(List<Main.ResultadoAnalisis> resultados) {
        if (resultados.isEmpty()) throw new IllegalArgumentException("Proyecto sin unidades");
        List<ResultadoSemantico> unidades = new ArrayList<>();
        Set<Path> archivos = new HashSet<>();
        for (Main.ResultadoAnalisis resultado : resultados)
            if (!archivos.add(Objects.requireNonNull(resultado).archivo.toAbsolutePath().normalize()))
                throw new IllegalArgumentException("Unidad duplicada: " + resultado.archivo);
        for (Main.ResultadoAnalisis resultado : resultados) {
            ResultadoSemantico semantico = Objects.requireNonNull(resultado).resultadoSemantico;
            if (semantico == null || !semantico.esExitoso() || semantico.getAstValidado() == null
                    || semantico.getAmbitoGlobal() == null)
                throw new IllegalArgumentException("Proyecto incompleto o con errores: " + resultado.archivo);
            for (Path dependencia : resultado.dependencias)
                if (!archivos.contains(dependencia.toAbsolutePath().normalize()))
                    throw new IllegalArgumentException("Falta la unidad importada: " + dependencia);
            unidades.add(semantico);
        }
        return new EntradaProyectoIr(unidades);
    }
}
