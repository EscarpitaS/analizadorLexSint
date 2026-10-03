import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Contrato para consumidores: el AST validado nunca se expone si falló alguna fase. */
final class ResultadoSemantico {
    private final boolean exitoso;
    private final List<compiladorErrores> errores;
    private final NodoArbolSintactico astValidado;
    private final NodoArbolSintactico arbolDiagnostico;
    private final Semantico.AmbitoSimbolos ambitoGlobal;

    ResultadoSemantico(NodoArbolSintactico arbol, Semantico.AmbitoSimbolos global,
                       List<compiladorErrores> errores, boolean fasesValidas) {
        this.errores = Collections.unmodifiableList(new ArrayList<>(errores));
        this.exitoso = fasesValidas && errores.isEmpty() && arbol != null && !arbol.contieneSintaxisIncompleta();
        this.astValidado = exitoso ? arbol : null;
        this.arbolDiagnostico = arbol;
        this.ambitoGlobal = global;
    }
    boolean esExitoso() { return exitoso; }
    List<compiladorErrores> getErrores() { return errores; }
    NodoArbolSintactico getAstValidado() { return astValidado; }
    NodoArbolSintactico getArbolDiagnostico() { return arbolDiagnostico; }
    Semantico.AmbitoSimbolos getAmbitoGlobal() { return ambitoGlobal; }
}
