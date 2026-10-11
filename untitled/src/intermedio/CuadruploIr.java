import java.util.*;

/** Exactamente (operación, fuente1, fuente2, destino). Ningún operando es un árbol. */
final class CuadruploIr {
    enum Operacion {
        LABEL, JUMP, JUMP_IF_FALSE, READ_PARAM, LEA_LOCAL, LEA, LOAD, STORE, COPY,
        ADD, SUB, MUL, NEG, NOT, EQ, NE, LT, LE, GT, GE, INT_A_FLOAT,
        PARAM, CALL, CALL_INDIRECT, LLAMAR_RUNTIME, GET_RETURN, SET_RETURN, RETURN
    }
    final Operacion operacion;
    final OperandoAtomico fuente1, fuente2, destino;
    final OrigenIr origen;
    CuadruploIr(Operacion op, OperandoAtomico a, OperandoAtomico b, OperandoAtomico destino, OrigenIr origen) {
        this.operacion = Objects.requireNonNull(op); fuente1 = a; fuente2 = b; this.destino = destino; this.origen = Objects.requireNonNull(origen);
    }
}

final class OperandoAtomico {
    enum Clase { VALOR, ETIQUETA, LLAMADA, ARGUMENTO }
    final Clase clase;
    final ValorIr valor;
    final String etiqueta;
    final LlamadaAtomica llamada;
    final LugarArgumentoIr argumento;
    private OperandoAtomico(Clase clase, ValorIr valor, String etiqueta, LlamadaAtomica llamada) {
        this(clase, valor, etiqueta, llamada, null);
    }
    private OperandoAtomico(Clase clase, ValorIr valor, String etiqueta, LlamadaAtomica llamada, LugarArgumentoIr argumento) {
        this.clase = clase; this.valor = valor; this.etiqueta = etiqueta; this.llamada = llamada; this.argumento = argumento;
    }
    static OperandoAtomico argumento(LugarArgumentoIr argumento) { return new OperandoAtomico(Clase.ARGUMENTO, null, null, null, Objects.requireNonNull(argumento)); }
    static OperandoAtomico valor(ValorIr valor) { return new OperandoAtomico(Clase.VALOR, Objects.requireNonNull(valor), null, null); }
    static OperandoAtomico etiqueta(String etiqueta) { return new OperandoAtomico(Clase.ETIQUETA, null, IdentificadoresIr.nombre(etiqueta), null); }
    static OperandoAtomico llamada(LlamadaAtomica llamada) { return new OperandoAtomico(Clase.LLAMADA, null, null, Objects.requireNonNull(llamada)); }
}

enum HelperAtomicoIr {
    COMPROBAR_NULO, COMPROBAR_INDICE, DIVIDIR_INT, DIVIDIR_FLOAT,
    A_STRING, CONCATENAR, IGUAL_STRING, NUEVO_OBJETO, NUEVA_LISTA
}

/** Destino resuelto y contrato lógico. Los adaptadores C preservarán RC-1. */
final class LlamadaAtomica {
    final String simbolo;
    final FirmaIr firma;
    final RuntimeConsolaId consola;
    final HelperAtomicoIr helper;
    final ValorIr puntero;
    final String registroPuntero;
    final List<TipoIr> parametros;
    final TipoIr retorno;
    private LlamadaAtomica(String simbolo, FirmaIr firma, RuntimeConsolaId consola, HelperAtomicoIr helper,
            ValorIr puntero, List<TipoIr> parametros, TipoIr retorno) {
        this.simbolo = IdentificadoresIr.nombre(simbolo); this.firma = firma; this.consola = consola; this.helper = helper;
        this.puntero = puntero;
        this.registroPuntero = puntero == null ? null : ConvencionLlamadasIr.REGISTRO_DESTINO_INDIRECTO;
        this.parametros = Collections.unmodifiableList(new ArrayList<>(parametros)); this.retorno = Objects.requireNonNull(retorno);
    }
    static LlamadaAtomica usuario(FirmaIr firma, ValorIr puntero) {
        List<TipoIr> tipos = new ArrayList<>(); tipos.add(TipoIr.objeto(firma.propietaria)); tipos.addAll(firma.parametros);
        return new LlamadaAtomica(LayoutProgramaIr.nombreFuncion(firma), firma, null, null, puntero, tipos, firma.retorno);
    }
    static LlamadaAtomica consola(RuntimeConsolaId id) {
        List<TipoIr> tipos = new ArrayList<>(); tipos.add(TipoIr.objeto("Consola")); if (id.imprime()) tipos.add(TipoIr.STRING);
        return new LlamadaAtomica("rt_lang_" + id.name(), null, id, null, null, tipos, id.retorno);
    }
    static LlamadaAtomica helper(HelperAtomicoIr id, TipoIr retorno, List<TipoIr> tipos) {
        StringBuilder simbolo = new StringBuilder("rt_lang_").append(id.name()).append('_').append(LayoutProgramaIr.tipo(retorno));
        for (TipoIr t : tipos) simbolo.append('_').append(LayoutProgramaIr.tipo(t));
        return new LlamadaAtomica(simbolo.toString(), null, null, id, null, tipos, retorno);
    }
}
