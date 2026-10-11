import java.util.*;

/** Máquina de referencia H2/H3 con pila explícita: la recursión no usa la pila Java. */
final strictfp class InterpreteIr {
    static final class Referencia {
        final String clase;
        final Map<String, MemoriaReferenciaIr.Celda> campos = new LinkedHashMap<>();
        Referencia(String clase) { this.clase = clase; }
    }
    static final class LimitePruebaIr extends RuntimeException {
        LimitePruebaIr() { super("LIMITE_DEL_HARNESS: se alcanzó el presupuesto de instrucciones"); }
    }
    private final long maxBytes;
    InterpreteIr() { this(Long.MAX_VALUE); }
    InterpreteIr(long maxBytes) { this.maxBytes = maxBytes; }
    private static final class Marco {
        final FuncionIr funcion;
        final Map<String, Object> valores = new HashMap<>();
        final Map<String, MemoriaReferenciaIr.Celda> locales = new HashMap<>();
        final Map<String, BloqueIr> bloques = new HashMap<>();
        final ValorIr destino;
        BloqueIr bloque;
        int posicion;
        Marco(FuncionIr f, Object receptor, List<Object> argumentos, ValorIr destino) {
            funcion = f; this.destino = destino;
            valores.put(f.receptor.id, receptor);
            for (int i = 0; i < f.parametros.size(); i++) valores.put(f.parametros.get(i).id, argumentos.get(i));
            for (SimboloIr s : f.locales) locales.put(s.id, new MemoriaReferenciaIr.Celda());
            for (BloqueIr b : f.bloques) bloques.put(b.id, b);
            bloque = f.bloques.get(0);
        }
        Object valor(ValorIr v) { return v.clase == ValorIr.Clase.CONSTANTE ? v.literal : valores.get(v.id); }
        void saltar(String id) { bloque = bloques.get(id); posicion = 0; }
    }
    Object ejecutar(ModuloIr modulo, ConsolaReferencia consola, long maxPasos) {
        if (modulo.entrada == null) throw new IllegalArgumentException("El módulo no selecciona entrada");
        return invocar(modulo, modulo.entrada, new Referencia(modulo.entrada.propietaria), Collections.emptyList(), consola, maxPasos, true);
    }
    Object invocar(ModuloIr modulo, FirmaIr entrada, Object receptor, List<Object> argumentos, ConsolaReferencia consola, long maxPasos) {
        return invocar(modulo, entrada, receptor, argumentos, consola, maxPasos, false);
    }
    private Object invocar(ModuloIr modulo, FirmaIr entrada, Object receptor, List<Object> argumentos, ConsolaReferencia consola, long maxPasos, boolean inicializar) {
        ResultadoVerificacionIr v = new VerificadorIr().verificar(modulo);
        if (!v.esExitoso()) throw new IllegalArgumentException("IR inválida: " + v.getErrores());
        if (maxPasos < 0) throw new IllegalArgumentException("Presupuesto negativo");
        Map<String, FuncionIr> funciones = new HashMap<>();
        Map<String, ClaseIr> clases = new HashMap<>();
        for (ClaseIr c : modulo.clases) clases.put(c.nombre, c);
        for (FuncionIr f : modulo.funciones) funciones.put(f.firma.id(), f);
        MemoriaReferenciaIr memoria = new MemoriaReferenciaIr(maxBytes);
        FuncionIr inicio = funciones.get(entrada.id());
        if (inicio == null || !inicio.firma.mismoContrato(entrada) || argumentos.size() != entrada.parametros.size())
            throw new IllegalArgumentException("Invocación inválida");
        if (!(receptor instanceof Referencia) || !((Referencia) receptor).clase.equals(entrada.propietaria))
            throw new IllegalArgumentException("Receptor inválido");
        Deque<Marco> pila = new ArrayDeque<>();
        if (((Referencia) receptor).campos.isEmpty()) {
            Map<String, TipoIr> campos = campos(clases, ((Referencia) receptor).clase);
            memoria.reservar(16L + 8L * campos.size(), OrigenIr.GENERADO);
            for (Map.Entry<String, TipoIr> c : campos.entrySet()) ((Referencia) receptor).campos.put(c.getKey(), new MemoriaReferenciaIr.Celda(MemoriaReferenciaIr.predeterminado(c.getValue())));
        }
        pila.push(new Marco(inicio, receptor, argumentos, null));
        ClaseIr claseEntrada = clases.get(entrada.propietaria);
        if (inicializar && claseEntrada.inicializador != null)
            pila.push(new Marco(funciones.get(claseEntrada.inicializador.id()), receptor, Collections.emptyList(), null));
        long pasos = 0;
        while (!pila.isEmpty()) {
            if (maxPasos != 0 && pasos++ >= maxPasos) throw new LimitePruebaIr();
            Marco m = pila.peek();
            if (m.posicion == m.bloque.instrucciones.size()) {
                TerminadorIr t = m.bloque.terminador;
                switch (t.clase) {
                    case SALTO: m.saltar(t.destinos.get(0)); break;
                    case CONDICIONAL: m.saltar(t.destinos.get((Boolean) m.valor(t.valor) ? 0 : 1)); break;
                    case RETORNO:
                        Object resultado = t.valor == null ? null : m.valor(t.valor);
                        pila.pop();
                        if (pila.isEmpty()) return resultado;
                        if (m.destino != null) pila.peek().valores.put(m.destino.id, resultado);
                        break;
                    default: throw new AssertionError(t.clase);
                }
                continue;
            }
            InstruccionIr i = m.bloque.instrucciones.get(m.posicion++);
            List<Object> args = new ArrayList<>(); for (ValorIr a : i.operandos) args.add(m.valor(a));
            if (i.operacion == InstruccionIr.Operacion.LLAMAR_DIRECTA || i.operacion == InstruccionIr.Operacion.LLAMAR_VIRTUAL) {
                comprobarNulo(args.get(0), i.origen);
                FirmaIr firma = i.firma;
                if (i.operacion == InstruccionIr.Operacion.LLAMAR_VIRTUAL) firma = virtual(clases, funciones, ((Referencia) args.get(0)).clase, i.firma);
                FuncionIr destino = funciones.get(firma.id());
                pila.push(new Marco(destino, args.get(0), args.subList(1, args.size()), i.resultado));
                continue;
            }
            Object resultado = operar(m, i, args, consola, memoria, clases);
            if (i.resultado != null) m.valores.put(i.resultado.id, resultado);
        }
        throw new AssertionError("Pila sin retorno");
    }
    private static void comprobarNulo(Object valor, OrigenIr origen) {
        if (valor == null) throw new ErrorEjecucionIr("REFERENCIA_NULA", origen);
    }
    private static Map<String, TipoIr> campos(Map<String, ClaseIr> clases, String nombre) {
        Deque<ClaseIr> orden = new ArrayDeque<>();
        for (ClaseIr c = clases.get(nombre); c != null; c = clases.get(c.base)) orden.push(c);
        Map<String, TipoIr> campos = new LinkedHashMap<>();
        while (!orden.isEmpty()) for (SimboloIr c : orden.pop().campos) campos.put(c.id, c.tipo);
        return campos;
    }
    private static FirmaIr virtual(Map<String, ClaseIr> clases, Map<String, FuncionIr> funciones, String dinamica, FirmaIr estatica) {
        for (ClaseIr c = clases.get(dinamica); c != null; c = clases.get(c.base))
            for (FuncionIr f : funciones.values()) if (f.firma.propietaria.equals(c.nombre) && f.firma.visibilidad == FirmaIr.Visibilidad.PUBLICA
                    && LayoutProgramaIr.clave(f.firma).equals(LayoutProgramaIr.clave(estatica))) return f.firma;
        throw new IllegalArgumentException("Método virtual sin implementación: " + estatica.id());
    }
    private Object operar(Marco m, InstruccionIr i, List<Object> a, ConsolaReferencia consola, MemoriaReferenciaIr memoria, Map<String, ClaseIr> clases) {
        Object x = a.isEmpty() ? null : a.get(0), y = a.size() < 2 ? null : a.get(1);
        switch (i.operacion) {
            case COPIAR: return x;
            case DIRECCION_LOCAL: return m.locales.get(i.simbolo.id);
            case DIRECCION_CAMPO:
                comprobarNulo(x, i.origen); return ((Referencia) x).campos.get(i.simbolo.id);
            case DIRECCION_ELEMENTO:
                return MemoriaReferenciaIr.comprobarIndice(x, (Long) y, i.origen).elementos[((Long) y).intValue()];
            case CARGAR: return ((MemoriaReferenciaIr.Celda) x).leer(i.origen);
            case GUARDAR: ((MemoriaReferenciaIr.Celda) x).guardar(y); return null;
            case CREAR_OBJETO: return memoria.objeto(i.resultado.tipo.clase, campos(clases, i.resultado.tipo.clase), i.origen);
            case CREAR_LISTA:
                MemoriaReferenciaIr.Lista lista = memoria.lista(i.resultado.tipo.elemento, a.size(), i.origen);
                for (int j = 0; j < a.size(); j++) lista.elementos[j].guardar(a.get(j)); return lista;
            case LONGITUD_LISTA: comprobarNulo(x, i.origen); return (long) ((MemoriaReferenciaIr.Lista) x).elementos.length;
            case COMPROBAR_INDICE: MemoriaReferenciaIr.comprobarIndice(x, (Long) y, i.origen); return null;
            case COMPROBAR_NULO: comprobarNulo(x, i.origen); return null;
            case LLAMAR_RUNTIME:
                comprobarNulo(x, i.origen);
                return consola.llamar(i.runtime, i.runtime.imprime() ? (String) y : null, i.origen);
            case CONVERTIR:
                switch (i.conversion) {
                    case INT_A_FLOAT: return ((Long) x).doubleValue();
                    case A_STRING: return ConsolaReferencia.texto(x);
                    case NULO_A_REFERENCIA: return null;
                    case SUBIR_CLASE: return x;
                    default: throw new IllegalArgumentException("OPERACION_IR_INVALIDA: " + i.conversion);
                }
            case CONCATENAR: return ConsolaReferencia.texto(x) + ConsolaReferencia.texto(y);
            case NO: return !(Boolean) x;
            case NEGAR:
                if (i.resultado.tipo.equals(TipoIr.INT)) return -((Long) x);
                return -((Double) x);
            case SUMAR: case RESTAR: case MULTIPLICAR: case DIVIDIR:
                boolean entero = i.resultado.tipo.equals(TipoIr.INT);
                if (entero) {
                    long p = (Long) x, q = (Long) y;
                    switch (i.operacion) {
                        case SUMAR: return p + q;
                        case RESTAR: return p - q;
                        case MULTIPLICAR: return p * q;
                        default: if (q == 0) throw new ErrorEjecucionIr("DIVISION_POR_CERO", i.origen); return ContratoEjecucion.dividirEnteros(p, q);
                    }
                }
                double p = (Double) x, q = (Double) y;
                switch (i.operacion) {
                    case SUMAR: return p + q;
                    case RESTAR: return p - q;
                    case MULTIPLICAR: return p * q;
                    default: if (q == 0.0) throw new ErrorEjecucionIr("DIVISION_POR_CERO", i.origen); return p / q;
                }
            case IGUAL: case DISTINTO:
                boolean iguales;
                if (x instanceof Double && y instanceof Double) iguales = ((Double) x).doubleValue() == ((Double) y).doubleValue();
                else if (x instanceof Referencia || y instanceof Referencia || x instanceof MemoriaReferenciaIr.Lista || y instanceof MemoriaReferenciaIr.Lista) iguales = x == y;
                else iguales = Objects.equals(x, y);
                return i.operacion == InstruccionIr.Operacion.IGUAL ? iguales : !iguales;
            case MENOR: case MENOR_IGUAL: case MAYOR: case MAYOR_IGUAL:
                if (i.operandos.get(0).tipo.equals(TipoIr.INT)) {
                    long l = (Long) x, r = (Long) y;
                    switch (i.operacion) {
                        case MENOR: return l < r;
                        case MENOR_IGUAL: return l <= r;
                        case MAYOR: return l > r;
                        default: return l >= r;
                    }
                }
                double l = (Double) x, r = (Double) y;
                switch (i.operacion) {
                    case MENOR: return l < r;
                    case MENOR_IGUAL: return l <= r;
                    case MAYOR: return l > r;
                    default: return l >= r;
                }
            default: throw new IllegalArgumentException("OPERACION_IR_INVALIDA: " + i.operacion);
        }
    }
}
