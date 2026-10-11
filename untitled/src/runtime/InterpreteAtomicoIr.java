import java.util.*;

/** Ejecución de referencia de cuádruplos físicos para comprobar la transformación H2/H3. */
final strictfp class InterpreteAtomicoIr {
    private final long maxBytes;
    InterpreteAtomicoIr() { this(Long.MAX_VALUE); }
    InterpreteAtomicoIr(long maxBytes) { this.maxBytes = maxBytes; }
    private static final class TablaVirtual {
        final LayoutProgramaIr.Clase clase;
        TablaVirtual(LayoutProgramaIr.Clase clase) { this.clase = clase; }
    }
    private static final class Marco {
        final FuncionAtomicaIr funcion;
        final LayoutProgramaIr.Marco layout;
        final Map<Integer, MemoriaReferenciaIr.Celda> memoria = new HashMap<>();
        final Map<String, Integer> labels = new HashMap<>();
        final List<Object> entrantes, salientes = new ArrayList<>();
        Object ultimoRetorno, retorno;
        int pc;
        Marco(FuncionAtomicaIr f, LayoutProgramaIr.Marco layout, List<Object> entrantes) {
            funcion = f; this.layout = layout; this.entrantes = new ArrayList<>(entrantes);
            for (int offset : layout.valores.values()) memoria.put(offset, new MemoriaReferenciaIr.Celda());
            for (int offset : layout.locales.values()) memoria.put(offset, new MemoriaReferenciaIr.Celda());
            for (int i = 0; i < f.codigo.size(); i++) if (f.codigo.get(i).operacion == CuadruploIr.Operacion.LABEL) labels.put(f.codigo.get(i).destino.etiqueta, i);
        }
        Object valor(OperandoAtomico o) {
            ValorIr v = o.valor; if (v.clase == ValorIr.Clase.CONSTANTE) return v.literal;
            MemoriaReferenciaIr.Celda c = memoria.get(layout.offset(v));
            if (!c.inicializada) throw new IllegalArgumentException("Valor atómico sin inicializar: " + v.id);
            return c.valor;
        }
        void guardar(OperandoAtomico d, Object valor) { MemoriaReferenciaIr.Celda c = memoria.get(layout.offset(d.valor)); c.valor = valor; c.inicializada = true; }
    }
    Object ejecutar(ModuloAtomicoIr modulo, ConsolaReferencia consola, long limite) {
        List<String> errores = new VerificadorAtomicoIr().verificar(modulo);
        if (!errores.isEmpty()) throw new IllegalArgumentException("IR atómica inválida: " + errores);
        if (modulo.entrada == null || limite < 0) throw new IllegalArgumentException("Entrada/presupuesto inválido");
        Map<String, FuncionAtomicaIr> funciones = new HashMap<>();
        for (FuncionAtomicaIr f : modulo.funciones) funciones.put(f.firma.id(), f);
        MemoriaReferenciaIr memoria = new MemoriaReferenciaIr(maxBytes);
        Deque<Marco> pila = new ArrayDeque<>();
        FuncionAtomicaIr principal = funciones.get(modulo.entrada.id());
        LayoutProgramaIr.Clase claseEntrada = modulo.layout.clases.get(principal.firma.propietaria);
        InterpreteIr.Referencia receptor = memoria.objeto(claseEntrada.nombre, claseEntrada.tiposCampos, OrigenIr.GENERADO);
        pila.push(new Marco(principal, modulo.layout.marcos.get(principal.firma.id()), Collections.singletonList(receptor)));
        if (claseEntrada.inicializador != null) {
            FuncionAtomicaIr init = funciones.get(claseEntrada.inicializador.id());
            pila.push(new Marco(init, modulo.layout.marcos.get(init.firma.id()), Collections.singletonList(receptor)));
        }
        long pasos = 0;
        while (!pila.isEmpty()) {
            if (limite != 0 && pasos++ >= limite) throw new InterpreteIr.LimitePruebaIr();
            Marco m = pila.peek(); CuadruploIr q = m.funcion.codigo.get(m.pc++);
            Object x = q.fuente1 != null && q.fuente1.clase == OperandoAtomico.Clase.VALOR ? m.valor(q.fuente1) : null;
            Object y = q.fuente2 != null && q.fuente2.clase == OperandoAtomico.Clase.VALOR ? m.valor(q.fuente2) : null;
            Object r = null;
            switch (q.operacion) {
                case LABEL: continue;
                case JUMP: m.pc = m.labels.get(q.fuente1.etiqueta); continue;
                case JUMP_IF_FALSE:
                    if (x instanceof Boolean ? !(Boolean) x : ((Long) x) == 0) m.pc = m.labels.get(q.fuente2.etiqueta); continue;
                case READ_PARAM: r = m.entrantes.get(q.fuente1.argumento.indice); break;
                case LEA_LOCAL: r = m.memoria.get(((Long) x).intValue()); break;
                case COPY: r = x; break;
                case LEA:
                    MemoriaReferenciaIr.noNulo(x, q.origen);
                    if (x instanceof InterpreteIr.Referencia) {
                        InterpreteIr.Referencia objeto = (InterpreteIr.Referencia) x;
                        LayoutProgramaIr.Clase clase = modulo.layout.clases.get(objeto.clase);
                        String campo = null;
                        for (String id : clase.campos.keySet()) if (clase.campos.get(id).longValue() == (Long) y) campo = id;
                        if (campo == null) throw new IllegalArgumentException("Offset de campo inválido");
                        r = objeto.campos.get(campo);
                    } else {
                        long offset = (Long) y;
                        if (offset < 16 || offset % 8 != 0) throw new IllegalArgumentException("Offset de lista inválido");
                        long indice = (offset - 16) / 8;
                        r = MemoriaReferenciaIr.comprobarIndice(x, indice, q.origen).elementos[(int) indice];
                    } break;
                case LOAD:
                    MemoriaReferenciaIr.noNulo(x, q.origen);
                    if (x instanceof MemoriaReferenciaIr.Celda) {
                        r = ((MemoriaReferenciaIr.Celda) x).leer(q.origen);
                    } else if (x instanceof MemoriaReferenciaIr.Lista) {
                        r = (long) ((MemoriaReferenciaIr.Lista) x).elementos.length;
                    } else if (x instanceof InterpreteIr.Referencia) {
                        comprobarNulo(x, q.origen); r = new TablaVirtual(modulo.layout.clases.get(((InterpreteIr.Referencia) x).clase));
                    } else if (x instanceof TablaVirtual) r = ((TablaVirtual) x).clase.virtuales.get((int) ((Long) y / 8));
                    else throw new IllegalArgumentException("OPERACION_IR_INVALIDA: LOAD en " + q.origen);
                    break;
                case STORE: ((MemoriaReferenciaIr.Celda) x).guardar(y); continue;
                case PARAM: m.salientes.add(x); continue;
                case CALL: case CALL_INDIRECT:
                    LlamadaAtomica l = q.fuente1.llamada;
                    FirmaIr firma = l.puntero == null ? l.firma : (FirmaIr) m.valor(OperandoAtomico.valor(l.puntero));
                    comprobarNulo(m.salientes.get(0), q.origen);
                    FuncionAtomicaIr destino = funciones.get(firma.id());
                    Marco hijo = new Marco(destino, modulo.layout.marcos.get(firma.id()), m.salientes); m.salientes.clear(); pila.push(hijo); continue;
                case LLAMAR_RUNTIME:
                    r = runtime(q.fuente1.llamada, m.salientes, consola, q.origen, memoria, modulo.layout); m.salientes.clear(); m.ultimoRetorno = r; continue;
                case GET_RETURN: r = m.ultimoRetorno; break;
                case SET_RETURN: m.retorno = x; continue;
                case RETURN:
                    pila.pop(); if (pila.isEmpty()) return m.retorno; pila.peek().ultimoRetorno = m.retorno; continue;
                case INT_A_FLOAT: r = ((Long) x).doubleValue(); break;
                case NOT: r = !(Boolean) x; break;
                case NEG: if (q.destino.valor.tipo.equals(TipoIr.INT)) r = -((Long) x); else r = -((Double) x); break;
                case ADD: case SUB: case MUL:
                    if (q.destino.valor.tipo.equals(TipoIr.INT)) {
                        long a = (Long) x, b = (Long) y;
                        switch (q.operacion) { case ADD: r = a + b; break; case SUB: r = a - b; break; default: r = a * b; }
                    } else {
                        double a = (Double) x, b = (Double) y;
                        switch (q.operacion) { case ADD: r = a + b; break; case SUB: r = a - b; break; default: r = a * b; }
                    } break;
                case EQ: case NE:
                    boolean igual = x instanceof Double && y instanceof Double ? ((Double) x).doubleValue() == ((Double) y).doubleValue()
                            : x instanceof InterpreteIr.Referencia || y instanceof InterpreteIr.Referencia || x instanceof MemoriaReferenciaIr.Lista || y instanceof MemoriaReferenciaIr.Lista ? x == y : Objects.equals(x, y);
                    r = q.operacion == CuadruploIr.Operacion.EQ ? igual : !igual; break;
                case LT: case LE: case GT: case GE:
                    if (q.fuente1.valor.tipo.equals(TipoIr.INT)) {
                        long a = (Long) x, b = (Long) y;
                        switch (q.operacion) { case LT: r = a < b; break; case LE: r = a <= b; break; case GT: r = a > b; break; default: r = a >= b; }
                    } else {
                        double a = (Double) x, b = (Double) y;
                        switch (q.operacion) { case LT: r = a < b; break; case LE: r = a <= b; break; case GT: r = a > b; break; default: r = a >= b; }
                    } break;
                default: throw new IllegalArgumentException("OPERACION_IR_INVALIDA: " + q.operacion);
            }
            if (q.destino != null) m.guardar(q.destino, r);
        }
        throw new AssertionError("Pila vacía sin RETURN");
    }
    private static void comprobarNulo(Object v, OrigenIr origen) { if (v == null) throw new ErrorEjecucionIr("REFERENCIA_NULA", origen); }
    private Object runtime(LlamadaAtomica l, List<Object> args, ConsolaReferencia consola, OrigenIr o, MemoriaReferenciaIr memoria, LayoutProgramaIr layout) {
        if (l.consola != null) { comprobarNulo(args.get(0), o); return consola.llamar(l.consola, l.consola.imprime() ? (String) args.get(1) : null, o); }
        switch (l.helper) {
            case COMPROBAR_NULO: comprobarNulo(args.get(0), o); return null;
            case DIVIDIR_INT:
                if ((Long) args.get(1) == 0) throw new ErrorEjecucionIr("DIVISION_POR_CERO", o);
                return ContratoEjecucion.dividirEnteros((Long) args.get(0), (Long) args.get(1));
            case DIVIDIR_FLOAT:
                if ((Double) args.get(1) == 0.0) throw new ErrorEjecucionIr("DIVISION_POR_CERO", o);
                return (Double) args.get(0) / (Double) args.get(1);
            case A_STRING: return ConsolaReferencia.texto(args.get(0));
            case CONCATENAR: return ConsolaReferencia.texto(args.get(0)) + ConsolaReferencia.texto(args.get(1));
            case IGUAL_STRING: return Objects.equals(args.get(0), args.get(1));
            case NUEVO_OBJETO: return memoria.objeto(l.retorno.clase, layout.clases.get(l.retorno.clase).tiposCampos, o);
            case NUEVA_LISTA: return memoria.lista(l.retorno.elemento, (Long) args.get(0), o);
            case COMPROBAR_INDICE: MemoriaReferenciaIr.comprobarIndice(args.get(0), (Long) args.get(1), o); return null;
            default: throw new IllegalArgumentException("OPERACION_IR_INVALIDA: " + l.helper);
        }
    }
}
