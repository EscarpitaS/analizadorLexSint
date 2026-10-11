import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ResultadoVerificacionIr {
    private final List<DiagnosticoIr> errores;
    ResultadoVerificacionIr(List<DiagnosticoIr> errores) {
        this.errores = Collections.unmodifiableList(new ArrayList<>(errores));
    }
    boolean esExitoso() { return errores.isEmpty(); }
    List<DiagnosticoIr> getErrores() { return errores; }
}
