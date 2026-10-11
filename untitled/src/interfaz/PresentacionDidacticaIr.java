import java.util.*;
import runtime.jdk11.FloatingDecimal;

/** Proyecciones de consulta. No modifica módulos, símbolos, instrucciones ni layout. */
final class PresentacionDidacticaIr {
    static final Map<CuadruploIr.Operacion, String> NOMBRES = nombres();
    private static Map<CuadruploIr.Operacion, String> nombres() {
        EnumMap<CuadruploIr.Operacion, String> m = new EnumMap<>(CuadruploIr.Operacion.class);
        m.put(CuadruploIr.Operacion.LABEL, "ETIQUETA");
        m.put(CuadruploIr.Operacion.JUMP, "SALTAR");
        m.put(CuadruploIr.Operacion.JUMP_IF_FALSE, "SALTAR_SI_FALSO");
        m.put(CuadruploIr.Operacion.READ_PARAM, "RECIBIR_PARAMETRO");
        m.put(CuadruploIr.Operacion.LEA_LOCAL, "OBTENER_DIRECCION_VARIABLE");
        m.put(CuadruploIr.Operacion.LEA, "CALCULAR_DIRECCION");
        m.put(CuadruploIr.Operacion.LOAD, "LEER_MEMORIA");
        m.put(CuadruploIr.Operacion.STORE, "ESCRIBIR_MEMORIA");
        m.put(CuadruploIr.Operacion.COPY, "ASIGNAR");
        m.put(CuadruploIr.Operacion.ADD, "SUMAR");
        m.put(CuadruploIr.Operacion.SUB, "RESTAR");
        m.put(CuadruploIr.Operacion.MUL, "MULTIPLICAR");
        m.put(CuadruploIr.Operacion.NEG, "NEGAR");
        m.put(CuadruploIr.Operacion.NOT, "NO");
        m.put(CuadruploIr.Operacion.EQ, "IGUAL");
        m.put(CuadruploIr.Operacion.NE, "DISTINTO");
        m.put(CuadruploIr.Operacion.LT, "MENOR");
        m.put(CuadruploIr.Operacion.LE, "MENOR_IGUAL");
        m.put(CuadruploIr.Operacion.GT, "MAYOR");
        m.put(CuadruploIr.Operacion.GE, "MAYOR_IGUAL");
        m.put(CuadruploIr.Operacion.INT_A_FLOAT, "CONVERTIR_A_FLOAT");
        m.put(CuadruploIr.Operacion.PARAM, "PARAMETRO");
        m.put(CuadruploIr.Operacion.CALL, "LLAMAR");
        m.put(CuadruploIr.Operacion.CALL_INDIRECT, "LLAMAR_INDIRECTA");
        m.put(CuadruploIr.Operacion.LLAMAR_RUNTIME, "LLAMAR_RUNTIME");
        m.put(CuadruploIr.Operacion.GET_RETURN, "OBTENER_RETORNO");
        m.put(CuadruploIr.Operacion.SET_RETURN, "ESTABLECER_RETORNO");
        m.put(CuadruploIr.Operacion.RETURN, "RETORNAR");
        if (m.size() != CuadruploIr.Operacion.values().length) throw new IllegalStateException("Diccionario IR incompleto");
        return Collections.unmodifiableMap(m);
    }

    static final class Atomo {
        final String texto, referencia;
        Atomo(String texto, String referencia) { this.texto = texto; this.referencia = referencia; }
        static Atomo texto(String texto) { return new Atomo(texto, null); }
    }
    private static final Atomo VACIO = Atomo.texto("—");

    static final class Fila {
        final int numero;
        final String funcion, operacion, ubicacion;
        final Atomo argumento1, argumento2, resultado;
        final boolean produceValor;
        Fila(int numero, String funcion, String operacion, Atomo a, Atomo b, Atomo r, boolean produceValor, OrigenIr origen) {
            this.numero = numero; this.funcion = funcion; this.operacion = operacion;
            argumento1 = a; argumento2 = b; resultado = r; this.produceValor = produceValor;
            ubicacion = ":" + origen.linea + ":" + origen.columna;
        }
        String codigo() {
            if (operacion.equals("ETIQUETA")) return resultado.texto + ":";
            if (operacion.equals("ASIGNAR")) return resultado.texto + " = " + argumento1.texto;
            String simbolo = Map.ofEntries(Map.entry("SUMAR", "+"), Map.entry("RESTAR", "-"), Map.entry("MULTIPLICAR", "*"),
                    Map.entry("DIVIDIR", "/"), Map.entry("IGUAL", "=="), Map.entry("DISTINTO", "!="), Map.entry("MENOR", "<"),
                    Map.entry("MENOR_IGUAL", "<="), Map.entry("MAYOR", ">"), Map.entry("MAYOR_IGUAL", ">=")).get(operacion);
            if (simbolo != null) return resultado.texto + " = " + argumento1.texto + " " + simbolo + " " + argumento2.texto;
            if (operacion.equals("NEGAR") || operacion.equals("NO")) return resultado.texto + " = "
                    + (operacion.equals("NEGAR") ? "-" : "no ") + argumento1.texto;
            if (operacion.equals("ESCRIBIR_MEMORIA")) return "*" + resultado.texto + " = " + argumento1.texto;
            if (operacion.equals("LEER_MEMORIA")) return resultado.texto + " = *" + argumento1.texto;
            if (operacion.equals("SALTAR")) return "ir a " + argumento1.texto;
            if (operacion.equals("SALTAR_SI_FALSO")) return "si no " + argumento1.texto + ", ir a " + argumento2.texto;
            if (operacion.equals("RETORNAR")) return "retornar" + (argumento1 == VACIO ? "" : " " + argumento1.texto);
            String args = argumento1 == VACIO ? "" : argumento1.texto + (argumento2 == VACIO ? "" : ", " + argumento2.texto);
            return (produceValor ? resultado.texto + " = " : "") + operacion.toLowerCase(Locale.ROOT) + "(" + args + ")";
        }
    }

    static final class Resultado {
        final List<Fila> filas;
        final List<List<String>> triplos;
        final String cuadruplosTsv, triplosTsv, codigo;
        Resultado(List<Fila> filas) {
            this.filas = Collections.unmodifiableList(new ArrayList<>(filas));
            Map<String, Integer> definiciones = new HashMap<>();
            for (Fila f : filas) if (f.produceValor && f.resultado.referencia != null)
                definiciones.put(f.funcion + "\u0000" + f.resultado.referencia, f.numero);
            List<List<String>> ts = new ArrayList<>();
            StringBuilder qs = new StringBuilder("#\tFunción\tOperador\tArgumento 1\tArgumento 2\tResultado\tUbicación\n");
            StringBuilder triples = new StringBuilder("#\tFunción\tOperador\tArgumento 1\tArgumento 2\tUbicación\n");
            StringBuilder codigo = new StringBuilder("IR SIMBÓLICA · proyección de la IR tipada\n"
                    + "Cada lectura conserva su temporal para capturar el valor en ese punto.\n");
            String funcion = "";
            for (Fila f : filas) {
                qs.append(f.numero).append('\t').append(f.funcion).append('\t').append(f.operacion).append('\t')
                        .append(f.argumento1.texto).append('\t').append(f.argumento2.texto).append('\t')
                        .append(f.resultado.texto).append('\t').append(f.ubicacion).append('\n');
                Atomo a = f.argumento1, b = f.argumento2;
                // En las tercias, las escrituras y etiquetas conservan su destino como operando.
                if (!f.produceValor && f.resultado != VACIO) b = f.resultado;
                List<String> t = List.of(Integer.toString(f.numero), f.funcion, f.operacion,
                        referencia(a, f.funcion, definiciones), referencia(b, f.funcion, definiciones), f.ubicacion);
                ts.add(t); triples.append(String.join("\t", t)).append('\n');
                if (!funcion.equals(f.funcion)) { funcion = f.funcion; codigo.append("\nfunción ").append(funcion).append('\n'); }
                if (f.operacion.equals("ETIQUETA")) codigo.append('\n'); else codigo.append('\t');
                codigo.append(f.codigo()).append("  @ ").append(f.ubicacion).append('\n');
            }
            triplos = Collections.unmodifiableList(ts); cuadruplosTsv = qs.toString();
            triplosTsv = triples.toString(); this.codigo = codigo.toString();
        }
        private static String referencia(Atomo a, String funcion, Map<String, Integer> defs) {
            if (a.referencia == null) return a.texto;
            Integer numero = defs.get(funcion + "\u0000" + a.referencia);
            if (numero == null) throw new IllegalArgumentException("Valor didáctico sin definición: " + a.texto);
            return "(" + numero + ")";
        }
    }

    static Resultado desdeTipada(ModuloIr modulo) {
        ResultadoVerificacionIr v = new VerificadorIr().verificar(modulo);
        if (!v.esExitoso()) throw new IllegalArgumentException("IR tipada inválida: " + v.getErrores());
        List<Fila> filas = new ArrayList<>();
        for (FuncionIr f : modulo.funciones) new Funcion(f, filas).generar();
        return new Resultado(filas);
    }

    private static final class Alias {
        final Map<String, Atomo> valores = new LinkedHashMap<>();
        final Map<String, String> etiquetas = new LinkedHashMap<>();
        int temporales, direcciones;
        Atomo valor(ValorIr v) {
            if (v == null) return VACIO;
            if (v.clase == ValorIr.Clase.CONSTANTE) return Atomo.texto(literal(v));
            return valores.computeIfAbsent(v.id, id -> new Atomo(v.tipo.categoria == TipoIr.Categoria.DIRECCION
                    ? "d" + direcciones++ : "t" + temporales++, id));
        }
        Atomo etiqueta(String id) { return Atomo.texto(etiquetas.computeIfAbsent(id, k -> "L" + etiquetas.size())); }
    }

    private static String literal(ValorIr v) {
        if (v.tipo.equals(TipoIr.STRING)) return ImpresorIr.escapar((String) v.literal);
        if (v.tipo.equals(TipoIr.NULO)) return "null";
        if (v.tipo.equals(TipoIr.FLOAT)) return FloatingDecimal.toJavaFormatString((Double) v.literal);
        return String.valueOf(v.literal);
    }

    private static final class Funcion {
        final FuncionIr f;
        final List<Fila> filas;
        final Alias alias = new Alias();
        final Map<String, Atomo> simbolos = new HashMap<>();
        final Map<String, SimboloIr> direccionesLocales = new HashMap<>();
        int auxiliares;
        Funcion(FuncionIr f, List<Fila> filas) {
            this.f = f; this.filas = filas;
            alias.valores.put(f.receptor.id, new Atomo("este", f.receptor.id));
            for (int i = 0; i < f.parametros.size(); i++) alias.valores.put(f.parametros.get(i).id,
                    new Atomo("argumento_" + (i + 1), f.parametros.get(i).id));
            Map<String, Integer> repetidos = new HashMap<>();
            for (SimboloIr s : f.locales) repetidos.merge(s.nombre, 1, Integer::sum);
            for (SimboloIr s : f.locales) simbolos.put(s.id, Atomo.texto(repetidos.get(s.nombre) > 1
                    || s.nombre.matches("(?:t|d|L|argumento_)\\d+|este") ? s.nombre + "#" + s.id : s.nombre));
            for (BloqueIr b : f.bloques) {
                alias.etiqueta(b.id);
                for (InstruccionIr i : b.instrucciones) if (i.operacion == InstruccionIr.Operacion.DIRECCION_LOCAL)
                    direccionesLocales.put(i.resultado.id, i.simbolo);
            }
            Set<String> noSimplificar = new HashSet<>();
            for (BloqueIr b : f.bloques) {
                for (InstruccionIr i : b.instrucciones) for (int n = 0; n < i.operandos.size(); n++) {
                    ValorIr valor = i.operandos.get(n);
                    if (valor.clase != ValorIr.Clase.CONSTANTE && direccionesLocales.containsKey(valor.id)
                            && !(n == 0 && (i.operacion == InstruccionIr.Operacion.CARGAR || i.operacion == InstruccionIr.Operacion.GUARDAR)))
                        noSimplificar.add(valor.id);
                }
                if (b.terminador.valor != null) noSimplificar.add(b.terminador.valor.id);
            }
            for (String id : noSimplificar) direccionesLocales.remove(id);
        }
        void emitir(String op, Atomo a, Atomo b, Atomo r, boolean produce, OrigenIr origen) {
            filas.add(new Fila(filas.size(), f.firma.id(), op, a, b, r, produce, origen));
        }
        void generar() {
            emitir("RECIBIR_PARAMETRO", Atomo.texto("argumento_0"), VACIO, alias.valor(f.receptor), true, OrigenIr.GENERADO);
            for (int j = 0; j < f.parametros.size(); j++) emitir("RECIBIR_PARAMETRO", Atomo.texto("argumento_" + (j + 1)), VACIO,
                    alias.valor(f.parametros.get(j)), true, OrigenIr.GENERADO);
            for (BloqueIr b : f.bloques) {
                emitir("ETIQUETA", VACIO, VACIO, alias.etiqueta(b.id), false, OrigenIr.GENERADO);
                for (InstruccionIr i : b.instrucciones) instruccion(i);
                TerminadorIr t = b.terminador;
                switch (t.clase) {
                    case SALTO: emitir("SALTAR", alias.etiqueta(t.destinos.get(0)), VACIO, VACIO, false, t.origen); break;
                    case CONDICIONAL:
                        emitir("SALTAR_SI_FALSO", alias.valor(t.valor), alias.etiqueta(t.destinos.get(1)), VACIO, false, t.origen);
                        emitir("SALTAR", alias.etiqueta(t.destinos.get(0)), VACIO, VACIO, false, t.origen); break;
                    case RETORNO: emitir("RETORNAR", alias.valor(t.valor), VACIO, VACIO, false, t.origen); break;
                    default: throw new AssertionError(t.clase);
                }
            }
        }
        void instruccion(InstruccionIr i) {
            Atomo r = alias.valor(i.resultado);
            Atomo a = i.operandos.isEmpty() ? VACIO : alias.valor(i.operandos.get(0));
            Atomo b = i.operandos.size() < 2 ? VACIO : alias.valor(i.operandos.get(1));
            switch (i.operacion) {
                case DIRECCION_LOCAL:
                    if (direccionesLocales.containsKey(i.resultado.id)) return;
                    emitir("OBTENER_DIRECCION_VARIABLE", simbolos.get(i.simbolo.id), VACIO, r, true, i.origen); return;
                case DIRECCION_CAMPO:
                    emitir("OBTENER_DIRECCION_CAMPO", a, Atomo.texto(i.simbolo.propietaria + "." + i.simbolo.nombre), r, true, i.origen); return;
                case DIRECCION_ELEMENTO:
                    emitir("OBTENER_DIRECCION_ELEMENTO", a, b, r, true, i.origen); return;
                case CARGAR: case GUARDAR:
                    SimboloIr local = direccionesLocales.get(i.operandos.get(0).id);
                    if (i.operacion == InstruccionIr.Operacion.CARGAR)
                        emitir(local == null ? "LEER_MEMORIA" : "ASIGNAR", local == null ? a : simbolos.get(local.id), VACIO, r, true, i.origen);
                    else emitir(local == null ? "ESCRIBIR_MEMORIA" : "ASIGNAR", b, VACIO,
                            local == null ? a : simbolos.get(local.id), false, i.origen);
                    return;
                case LLAMAR_DIRECTA: case LLAMAR_VIRTUAL: case LLAMAR_RUNTIME:
                    for (int j = 0; j < i.operandos.size(); j++) emitir("PARAMETRO", alias.valor(i.operandos.get(j)),
                            Atomo.texto("argumento_" + j), VACIO, false, i.origen);
                    String destino = i.runtime == null ? i.firma.id() : runtime(i.runtime);
                    emitir(i.operacion.name(), Atomo.texto(destino), Atomo.texto(Integer.toString(i.operandos.size())), VACIO, false, i.origen);
                    if (i.resultado != null) emitir("OBTENER_RETORNO", VACIO, VACIO, r, true, i.origen);
                    return;
                case CREAR_LISTA:
                    emitir("CREAR_LISTA", Atomo.texto(Integer.toString(i.operandos.size())), VACIO, r, true, i.origen);
                    for (int j = 0; j < i.operandos.size(); j++) {
                        Atomo direccion = new Atomo("d" + alias.direcciones++, "#aux#" + auxiliares++);
                        emitir("OBTENER_DIRECCION_ELEMENTO", r, Atomo.texto(Integer.toString(j)), direccion, true, i.origen);
                        emitir("ESCRIBIR_MEMORIA", alias.valor(i.operandos.get(j)), VACIO, direccion, false, i.origen);
                    } return;
                case CREAR_OBJETO:
                    emitir("CREAR_OBJETO", Atomo.texto(i.resultado.tipo.clase), VACIO, r, true, i.origen); return;
                case CONVERTIR:
                    emitir("CONVERTIR_" + i.conversion.name(), a, VACIO, r, true, i.origen); return;
                default:
                    if (i.operandos.size() > 2) throw new IllegalArgumentException("Operación didáctica sin descomponer: " + i.operacion);
                    emitir(i.operacion == InstruccionIr.Operacion.COPIAR ? "ASIGNAR" : i.operacion.name(), a, b, r, i.resultado != null, i.origen);
            }
        }
    }

    private static String runtime(RuntimeConsolaId id) {
        switch (id) {
            case CONSOLA_IMPRIMIR: return "Consola.imprimir";
            case CONSOLA_IMPRIMIR_LINEA: return "Consola.imprimirLinea";
            case CONSOLA_LEER_INT: return "Consola.leerInt";
            case CONSOLA_LEER_FLOAT: return "Consola.leerFloat";
            case CONSOLA_LEER_BOOL: return "Consola.leerBool";
            case CONSOLA_LEER_LINEA: return "Consola.leerLinea";
            default: throw new AssertionError(id);
        }
    }

    static String atomicaSimplificada(ModuloIr tipada, ModuloAtomicoIr atomica) {
        Map<String, FuncionIr> funciones = new HashMap<>();
        for (FuncionIr f : tipada.funciones) funciones.put(f.firma.id(), f);
        StringBuilder s = new StringBuilder("IR ATÓMICA SIMPLIFICADA · mismos cuádruplos físicos\n"
                + "argumento_N indica posición lógica; dN es una dirección; tN es un valor temporal.\n");
        for (FuncionAtomicaIr f : atomica.funciones) {
            Alias alias = new Alias();
            alias.valores.put(f.receptor.id, Atomo.texto("este"));
            for (int j = 0; j < f.parametros.size(); j++) alias.valores.put(f.parametros.get(j).id, Atomo.texto("argumento_" + (j + 1)));
            for (CuadruploIr q : f.codigo) if (q.operacion == CuadruploIr.Operacion.LABEL) alias.etiqueta(q.destino.etiqueta);
            LayoutProgramaIr.Marco marco = atomica.layout.marcos.get(f.firma.id());
            Map<Integer, String> locales = new HashMap<>();
            FuncionIr origen = funciones.get(f.firma.id());
            Map<String, Long> cantidades = new HashMap<>();
            for (SimboloIr local : origen.locales) cantidades.merge(local.nombre, 1L, Long::sum);
            for (SimboloIr local : origen.locales) locales.put(marco.locales.get(local.id), local.nombre
                    + (cantidades.get(local.nombre) > 1 || local.nombre.matches("(?:t|d|L|argumento_)\\d+|este") ? "#" + local.id : ""));
            s.append("\nfunción ").append(f.firma.id()).append('\n');
            for (CuadruploIr q : f.codigo) {
                boolean etiqueta = q.operacion == CuadruploIr.Operacion.LABEL;
                s.append(etiqueta ? "\n(" : "\t(").append(NOMBRES.get(q.operacion)).append(", ");
                String a = q.operacion == CuadruploIr.Operacion.LEA_LOCAL
                        ? locales.get(Math.toIntExact((Long) q.fuente1.valor.literal)) : operando(q.fuente1, alias);
                s.append(a).append(", ").append(operando(q.fuente2, alias)).append(", ")
                        .append(operando(q.destino, alias)).append(") @ :").append(q.origen.linea).append(':').append(q.origen.columna).append('\n');
            }
        }
        return s.toString();
    }
    private static String operando(OperandoAtomico o, Alias alias) {
        if (o == null) return "—";
        switch (o.clase) {
            case VALOR: return alias.valor(o.valor).texto;
            case ARGUMENTO: return "argumento_" + o.argumento.indice;
            case ETIQUETA: return alias.etiqueta(o.etiqueta).texto;
            case LLAMADA:
                if (o.llamada.puntero != null) return alias.valor(o.llamada.puntero).texto;
                if (o.llamada.firma != null) return o.llamada.firma.id();
                if (o.llamada.consola != null) return runtime(o.llamada.consola);
                String helper = o.llamada.helper.name().toLowerCase(Locale.ROOT);
                if (o.llamada.helper == HelperAtomicoIr.NUEVO_OBJETO) helper += "<" + o.llamada.retorno.clase + ">";
                else if (o.llamada.helper == HelperAtomicoIr.NUEVA_LISTA) helper += "<" + o.llamada.retorno.elemento + ">";
                else if (o.llamada.helper == HelperAtomicoIr.A_STRING) helper += "<" + o.llamada.parametros.get(0) + ">";
                return helper;
            default: throw new AssertionError(o.clase);
        }
    }
}
