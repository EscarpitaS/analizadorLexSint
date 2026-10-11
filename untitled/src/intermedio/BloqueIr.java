import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Un solo terminador al final; null permite representar un bloque incompleto para diagnosticarlo. */
final class BloqueIr {
    final String id;
    final List<InstruccionIr> instrucciones;
    final TerminadorIr terminador;
    BloqueIr(String id, List<InstruccionIr> instrucciones, TerminadorIr terminador) {
        this.id = IdentificadoresIr.nombre(id); this.instrucciones = Collections.unmodifiableList(new ArrayList<>(instrucciones));
        for (InstruccionIr i : this.instrucciones) Objects.requireNonNull(i);
        this.terminador = terminador;
    }
}
