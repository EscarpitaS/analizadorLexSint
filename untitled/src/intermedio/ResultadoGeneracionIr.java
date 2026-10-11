import java.util.*;

/** Un fallo nunca expone un módulo parcial. */
final class ResultadoGeneracionIr {
    final ModuloIr modulo;
    final List<String> errores;
    ResultadoGeneracionIr(ModuloIr modulo, List<String> errores) {
        this.modulo = modulo; this.errores = Collections.unmodifiableList(new ArrayList<>(errores));
    }
    boolean esExitoso() { return modulo != null && errores.isEmpty(); }
}
