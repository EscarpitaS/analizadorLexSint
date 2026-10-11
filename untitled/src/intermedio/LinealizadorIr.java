import java.util.*;

/** IR tipada → cuádruplos atómicos + layout x86-64. Sin expresiones compuestas. */
final class LinealizadorIr {
    ModuloAtomicoIr transformar(ModuloIr modulo) {
        return transformar(modulo, DestinoNativoIr.LINUX_X64);
    }
    ModuloAtomicoIr transformar(ModuloIr modulo, DestinoNativoIr destino) {
        Objects.requireNonNull(destino);
        ResultadoVerificacionIr r = new VerificadorIr().verificar(modulo);
        if (!r.esExitoso()) throw new IllegalArgumentException("IR tipada inválida: " + r.getErrores());
        Map<String, LayoutProgramaIr.Clase> clases = LayoutProgramaIr.clases(modulo);
        List<FuncionAtomicaIr> funciones = new ArrayList<>(); Map<String, LayoutProgramaIr.Marco> marcos = new LinkedHashMap<>();
        for (FuncionIr f : modulo.funciones) {
            Funcion conversion = new Funcion(f, clases, destino); funciones.add(conversion.transformar()); marcos.put(f.firma.id(), conversion.marco.construir(destino));
        }
        ModuloAtomicoIr resultado = new ModuloAtomicoIr(modulo.nombre, modulo.entrada, new LayoutProgramaIr(destino, clases, marcos), funciones);
        List<String> errores = new VerificadorAtomicoIr().verificar(resultado);
        if (!errores.isEmpty()) throw new IllegalArgumentException("IR atómica inválida: " + errores);
        return resultado;
    }
    private static final class Funcion {
        final DestinoNativoIr destino;
        final FuncionIr f;
        final Map<String, LayoutProgramaIr.Clase> clases;
        final LayoutProgramaIr.ConstructorMarco marco = new LayoutProgramaIr.ConstructorMarco();
        final List<CuadruploIr> codigo = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        final String prefijo;
        int contador;
        Funcion(FuncionIr f, Map<String, LayoutProgramaIr.Clase> clases, DestinoNativoIr destino) {
            this.destino = destino;
            this.f = f; this.clases = clases; prefijo = "L_" + LayoutProgramaIr.nombreFuncion(f.firma) + "_";
            marco.valor(f.receptor); ids.add(f.receptor.id);
            for (ValorIr p : f.parametros) { marco.valor(p); ids.add(p.id); }
            for (SimboloIr s : f.locales) marco.local(s);
            for (BloqueIr b : f.bloques) for (InstruccionIr i : b.instrucciones) if (i.resultado != null) { marco.valor(i.resultado); ids.add(i.resultado.id); }
        }
        OperandoAtomico v(ValorIr v) { return v == null ? null : OperandoAtomico.valor(v); }
        OperandoAtomico n(long n) { return v(ValorIr.entero(n)); }
        OperandoAtomico label(String id) { return OperandoAtomico.etiqueta(prefijo + id); }
        void emitir(CuadruploIr.Operacion op, OperandoAtomico a, OperandoAtomico b, OperandoAtomico destino, OrigenIr origen) {
            codigo.add(new CuadruploIr(op, a, b, destino, origen));
        }
        ValorIr temporal(TipoIr tipo) {
            String id; do { id = "q" + contador++; } while (!ids.add(id));
            ValorIr v = ValorIr.temporal(id, tipo); marco.valor(v); return v;
        }
        void llamar(LlamadaAtomica destino, ValorIr resultado, List<ValorIr> args, OrigenIr origen) {
            marco.maxArgumentos = Math.max(marco.maxArgumentos, args.size());
            List<LugarArgumentoIr> lugares = this.destino.argumentos(destino.parametros, false);
            for (int i = 0; i < args.size(); i++) emitir(CuadruploIr.Operacion.PARAM, v(args.get(i)), OperandoAtomico.argumento(lugares.get(i)), null, origen);
            emitir(destino.firma == null ? CuadruploIr.Operacion.LLAMAR_RUNTIME : destino.puntero == null ? CuadruploIr.Operacion.CALL : CuadruploIr.Operacion.CALL_INDIRECT,
                    OperandoAtomico.llamada(destino), n(args.size()), null, origen);
            if (resultado != null) emitir(CuadruploIr.Operacion.GET_RETURN, null, null, v(resultado), origen);
        }
        void helper(HelperAtomicoIr id, ValorIr resultado, List<ValorIr> args, OrigenIr origen) {
            List<TipoIr> tipos = new ArrayList<>(); for (ValorIr v : args) tipos.add(v.tipo);
            llamar(LlamadaAtomica.helper(id, resultado == null ? TipoIr.VOID : resultado.tipo, tipos), resultado, args, origen);
        }
        FuncionAtomicaIr transformar() {
            List<TipoIr> tipos = new ArrayList<>(); tipos.add(f.receptor.tipo); tipos.addAll(f.firma.parametros);
            List<LugarArgumentoIr> entrantes = destino.argumentos(tipos, true);
            for (int i = 0; i <= f.parametros.size(); i++) emitir(CuadruploIr.Operacion.READ_PARAM, OperandoAtomico.argumento(entrantes.get(i)), null,
                    v(i == 0 ? f.receptor : f.parametros.get(i - 1)), OrigenIr.GENERADO);
            for (BloqueIr b : f.bloques) {
                emitir(CuadruploIr.Operacion.LABEL, null, null, label(b.id), OrigenIr.GENERADO);
                for (InstruccionIr i : b.instrucciones) instruccion(i);
                TerminadorIr t = b.terminador;
                switch (t.clase) {
                    case SALTO: emitir(CuadruploIr.Operacion.JUMP, label(t.destinos.get(0)), null, null, t.origen); break;
                    case CONDICIONAL:
                        emitir(CuadruploIr.Operacion.JUMP_IF_FALSE, v(t.valor), label(t.destinos.get(1)), null, t.origen);
                        emitir(CuadruploIr.Operacion.JUMP, label(t.destinos.get(0)), null, null, t.origen); break;
                    case RETORNO:
                        if (t.valor != null) emitir(CuadruploIr.Operacion.SET_RETURN, v(t.valor), null, null, t.origen);
                        emitir(CuadruploIr.Operacion.RETURN, null, null, null, t.origen); break;
                    default: throw new AssertionError(t.clase);
                }
            }
            return new FuncionAtomicaIr(f.firma, f.receptor, f.parametros, codigo);
        }
        void instruccion(InstruccionIr i) {
            List<ValorIr> a = i.operandos;
            ValorIr x = a.isEmpty() ? null : a.get(0), y = a.size() < 2 ? null : a.get(1);
            OrigenIr o = i.origen;
            switch (i.operacion) {
                case COPIAR: emitir(CuadruploIr.Operacion.COPY, v(x), null, v(i.resultado), o); return;
                case DIRECCION_LOCAL: emitir(CuadruploIr.Operacion.LEA_LOCAL, n(marco.locales.get(i.simbolo.id)), null, v(i.resultado), o); return;
                case DIRECCION_CAMPO:
                    helper(HelperAtomicoIr.COMPROBAR_NULO, null, Collections.singletonList(x), o);
                    emitir(CuadruploIr.Operacion.LEA, v(x), n(clases.get(i.simbolo.propietaria).campos.get(i.simbolo.id)), v(i.resultado), o); return;
                case DIRECCION_ELEMENTO:
                    helper(HelperAtomicoIr.COMPROBAR_INDICE, null, a, o);
                    ValorIr bytes = temporal(TipoIr.INT), offset = temporal(TipoIr.INT);
                    emitir(CuadruploIr.Operacion.MUL, v(y), n(8), v(bytes), o);
                    emitir(CuadruploIr.Operacion.ADD, v(bytes), n(LayoutProgramaIr.CABECERA_LISTA), v(offset), o);
                    emitir(CuadruploIr.Operacion.LEA, v(x), v(offset), v(i.resultado), o); return;
                case CARGAR: emitir(CuadruploIr.Operacion.LOAD, v(x), n(0), v(i.resultado), o); return;
                case GUARDAR: emitir(CuadruploIr.Operacion.STORE, v(x), v(y), null, o); return;
                case CONVERTIR:
                    switch (i.conversion) {
                        case INT_A_FLOAT: emitir(CuadruploIr.Operacion.INT_A_FLOAT, v(x), null, v(i.resultado), o); return;
                        case NULO_A_REFERENCIA: case SUBIR_CLASE: emitir(CuadruploIr.Operacion.COPY, v(x), null, v(i.resultado), o); return;
                        case A_STRING: helper(HelperAtomicoIr.A_STRING, i.resultado, a, o); return;
                        default: throw new AssertionError(i.conversion);
                    }
                case DIVIDIR: helper(i.resultado.tipo.equals(TipoIr.INT) ? HelperAtomicoIr.DIVIDIR_INT : HelperAtomicoIr.DIVIDIR_FLOAT, i.resultado, a, o); return;
                case CONCATENAR: helper(HelperAtomicoIr.CONCATENAR, i.resultado, a, o); return;
                case COMPROBAR_NULO: helper(HelperAtomicoIr.COMPROBAR_NULO, null, a, o); return;
                case COMPROBAR_INDICE: helper(HelperAtomicoIr.COMPROBAR_INDICE, null, a, o); return;
                case CREAR_OBJETO: helper(HelperAtomicoIr.NUEVO_OBJETO, i.resultado, Collections.emptyList(), o); return;
                case LONGITUD_LISTA:
                    helper(HelperAtomicoIr.COMPROBAR_NULO, null, a, o);
                    emitir(CuadruploIr.Operacion.LOAD, v(x), n(LayoutProgramaIr.OFFSET_LONGITUD), v(i.resultado), o); return;
                case CREAR_LISTA:
                    helper(HelperAtomicoIr.NUEVA_LISTA, i.resultado, Collections.singletonList(ValorIr.entero(a.size())), o);
                    for (int j = 0; j < a.size(); j++) {
                        ValorIr dir = temporal(TipoIr.direccion(i.resultado.tipo.elemento));
                        emitir(CuadruploIr.Operacion.LEA, v(i.resultado), n(LayoutProgramaIr.CABECERA_LISTA + 8L * j), v(dir), o);
                        emitir(CuadruploIr.Operacion.STORE, v(dir), v(a.get(j)), null, o);
                    } return;
                case IGUAL: case DISTINTO:
                    if (x.tipo.equals(TipoIr.STRING) || y.tipo.equals(TipoIr.STRING)) {
                        List<ValorIr> textos = new ArrayList<>();
                        for (ValorIr texto : a) {
                            if (texto.tipo.equals(TipoIr.NULO)) {
                                ValorIr referencia = temporal(TipoIr.STRING);
                                emitir(CuadruploIr.Operacion.COPY, v(texto), null, v(referencia), o); textos.add(referencia);
                            } else textos.add(texto);
                        }
                        ValorIr igual = i.operacion == InstruccionIr.Operacion.IGUAL ? i.resultado : temporal(TipoIr.BOOL);
                        helper(HelperAtomicoIr.IGUAL_STRING, igual, textos, o);
                        if (i.operacion == InstruccionIr.Operacion.DISTINTO) emitir(CuadruploIr.Operacion.NOT, v(igual), null, v(i.resultado), o);
                    } else emitir(i.operacion == InstruccionIr.Operacion.IGUAL ? CuadruploIr.Operacion.EQ : CuadruploIr.Operacion.NE, v(x), v(y), v(i.resultado), o);
                    return;
                case LLAMAR_RUNTIME: llamar(LlamadaAtomica.consola(i.runtime), i.resultado, a, o); return;
                case LLAMAR_DIRECTA:
                    helper(HelperAtomicoIr.COMPROBAR_NULO, null, Collections.singletonList(x), o);
                    llamar(LlamadaAtomica.usuario(i.firma, null), i.resultado, a, o); return;
                case LLAMAR_VIRTUAL:
                    // Dos cargas distintas: cabecera → tabla; tabla+slot → dirección.
                    helper(HelperAtomicoIr.COMPROBAR_NULO, null, Collections.singletonList(x), o);
                    ValorIr tabla = temporal(TipoIr.direccion(TipoIr.INT)), destino = temporal(TipoIr.direccion(TipoIr.INT));
                    emitir(CuadruploIr.Operacion.LOAD, v(x), n(LayoutProgramaIr.OFFSET_VTABLE), v(tabla), o);
                    emitir(CuadruploIr.Operacion.LOAD, v(tabla), n(clases.get(i.firma.propietaria).offsetVirtual(i.firma)), v(destino), o);
                    llamar(LlamadaAtomica.usuario(i.firma, destino), i.resultado, a, o); return;
                default:
                    CuadruploIr.Operacion op;
                    switch (i.operacion) {
                        case SUMAR: op = CuadruploIr.Operacion.ADD; break;
                        case RESTAR: op = CuadruploIr.Operacion.SUB; break;
                        case MULTIPLICAR: op = CuadruploIr.Operacion.MUL; break;
                        case NEGAR: op = CuadruploIr.Operacion.NEG; break;
                        case NO: op = CuadruploIr.Operacion.NOT; break;
                        case IGUAL: op = CuadruploIr.Operacion.EQ; break;
                        case DISTINTO: op = CuadruploIr.Operacion.NE; break;
                        case MENOR: op = CuadruploIr.Operacion.LT; break;
                        case MENOR_IGUAL: op = CuadruploIr.Operacion.LE; break;
                        case MAYOR: op = CuadruploIr.Operacion.GT; break;
                        case MAYOR_IGUAL: op = CuadruploIr.Operacion.GE; break;
                        default: throw new AssertionError("Operación sin proyección atómica: " + i.operacion);
                    }
                    emitir(op, v(x), v(y), v(i.resultado), o);
            }
        }
    }
}
