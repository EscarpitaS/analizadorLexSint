import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Tres direcciones, sin promociones implícitas: se representan como CONVERTIR. */
final class InstruccionIr {
    enum Operacion {
        COPIAR, DIRECCION_LOCAL, DIRECCION_CAMPO, DIRECCION_ELEMENTO, CARGAR, GUARDAR,
        SUMAR, RESTAR, MULTIPLICAR, DIVIDIR, NEGAR, NO,
        IGUAL, DISTINTO, MENOR, MENOR_IGUAL, MAYOR, MAYOR_IGUAL,
        CONVERTIR, LLAMAR_DIRECTA, LLAMAR_VIRTUAL, LLAMAR_RUNTIME,
        CONCATENAR, CREAR_LISTA, LONGITUD_LISTA, CREAR_OBJETO, COMPROBAR_NULO, COMPROBAR_INDICE
    }
    enum Conversion { INT_A_FLOAT, A_STRING, NULO_A_REFERENCIA, SUBIR_CLASE }
    final Operacion operacion;
    final RuntimeConsolaId runtime;
    final ValorIr resultado;
    final List<ValorIr> operandos;
    final OrigenIr origen;
    final SimboloIr simbolo;
    final FirmaIr firma;
    final Conversion conversion;
    private InstruccionIr(Operacion op, ValorIr resultado, List<ValorIr> operandos, OrigenIr origen,
            SimboloIr simbolo, FirmaIr firma, Conversion conversion) {
        this(op, resultado, operandos, origen, simbolo, firma, conversion, null);
    }
    private InstruccionIr(Operacion op, ValorIr resultado, List<ValorIr> operandos, OrigenIr origen,
            SimboloIr simbolo, FirmaIr firma, Conversion conversion, RuntimeConsolaId runtime) {
        this.runtime = runtime;
        this.operacion = Objects.requireNonNull(op); this.resultado = resultado;
        this.operandos = Collections.unmodifiableList(new ArrayList<>(operandos));
        for (ValorIr valor : this.operandos) Objects.requireNonNull(valor);
        this.origen = Objects.requireNonNull(origen); this.simbolo = simbolo; this.firma = firma; this.conversion = conversion;
    }
    static InstruccionIr runtime(RuntimeConsolaId id, ValorIr resultado, OrigenIr origen, ValorIr... args) {
        return new InstruccionIr(Operacion.LLAMAR_RUNTIME, resultado, Arrays.asList(args), origen, null, null, null, id);
    }
    static InstruccionIr operacion(Operacion op, ValorIr resultado, OrigenIr origen, ValorIr... args) {
        return new InstruccionIr(op, resultado, Arrays.asList(args), origen, null, null, null);
    }
    static InstruccionIr direccionLocal(ValorIr resultado, SimboloIr simbolo, OrigenIr origen) {
        return new InstruccionIr(Operacion.DIRECCION_LOCAL, resultado, Collections.emptyList(), origen, simbolo, null, null);
    }
    static InstruccionIr direccionCampo(ValorIr resultado, SimboloIr campo, ValorIr receptor, OrigenIr origen) {
        return new InstruccionIr(Operacion.DIRECCION_CAMPO, resultado, Arrays.asList(receptor), origen, campo, null, null);
    }
    static InstruccionIr convertir(ValorIr resultado, Conversion conversion, ValorIr operando, OrigenIr origen) {
        return new InstruccionIr(Operacion.CONVERTIR, resultado, Arrays.asList(operando), origen, null, null, conversion);
    }
    static InstruccionIr llamar(boolean virtual, ValorIr resultado, FirmaIr firma, ValorIr receptor, OrigenIr origen, ValorIr... args) {
        List<ValorIr> valores = new ArrayList<>(); valores.add(receptor); valores.addAll(Arrays.asList(args));
        return new InstruccionIr(virtual ? Operacion.LLAMAR_VIRTUAL : Operacion.LLAMAR_DIRECTA,
                resultado, valores, origen, null, firma, null);
    }
}
