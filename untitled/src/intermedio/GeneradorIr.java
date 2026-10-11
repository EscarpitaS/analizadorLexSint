import java.util.*;

/** H2/H3: baja el AST decorado, sin repetir resolución de nombres ni tipos. */
final class GeneradorIr {
    ResultadoGeneracionIr generar(List<Main.ResultadoAnalisis> resultados, String entrada) {
        try { return new Sesion(resultados).generar(entrada); }
        catch (Fallo e) { return new ResultadoGeneracionIr(null, Collections.singletonList(e.getMessage())); }
        catch (IllegalArgumentException e) { return new ResultadoGeneracionIr(null, Collections.singletonList(e.getMessage())); }
    }
    private static final class Fallo extends RuntimeException {
        Fallo(String mensaje) { super(mensaje); }
    }
    private static NodoArbolSintactico hijo(NodoArbolSintactico n, String nombre) {
        for (NodoArbolSintactico h : n.getHijosSemanticos()) if (h.getNombre().equals(nombre)) return h;
        return null;
    }
    private static final class Sesion {
        final List<Main.ResultadoAnalisis> unidades;
        final List<ClaseIr> clases = new ArrayList<>();
        final Map<Semantico.Simbolo, SimboloIr> campos = new IdentityHashMap<>();
        final Map<String, NodoArbolSintactico> nodosClases = new LinkedHashMap<>();
        final Map<String, ClaseIr> clasesPorNombre = new LinkedHashMap<>();
        final Map<Semantico.Simbolo, FirmaIr> firmas = new IdentityHashMap<>();
        final List<Semantico.Simbolo> metodos = new ArrayList<>();
        final Map<Semantico.Simbolo, NodoArbolSintactico> cuerpos = new IdentityHashMap<>();
        final Map<NodoArbolSintactico, String> archivos = new IdentityHashMap<>();
        int simbolos;
        Sesion(List<Main.ResultadoAnalisis> unidades) {
            EntradaProyectoIr.desdeResultados(unidades);
            this.unidades = unidades;
            for (Main.ResultadoAnalisis u : unidades) registrarOrigen(u.resultadoSemantico.getAstValidado(), u.archivo.toString());
        }
        void registrarOrigen(NodoArbolSintactico n, String archivo) {
            archivos.put(n, archivo);
            for (NodoArbolSintactico h : n.getHijosSemanticos()) registrarOrigen(h, archivo);
        }
        OrigenIr origen(NodoArbolSintactico n) {
            return n.getLinea() > 0 ? new OrigenIr(archivos.getOrDefault(n, "<fuente>"), n.getLinea(), n.getColumna()) : OrigenIr.GENERADO;
        }
        Fallo pendiente(NodoArbolSintactico n, String detalle) { return new Fallo(origen(n) + ": NODO_IR_NO_SOPORTADO: " + detalle); }
        void recoger(NodoArbolSintactico n) {
            if (n.getNombre().equals("CLASE")) {
                Semantico.Simbolo c = n.getSimboloResuelto();
                NodoArbolSintactico herencia = hijo(n, "HERENCIA");
                String base = herencia == null ? null : herencia.getHijos().get(0).getValor();
                List<SimboloIr> declarados = new ArrayList<>();
                for (NodoArbolSintactico miembro : hijo(n, "CUERPO_CLASE").getHijos()) {
                    if (hijo(miembro, "CAMPO") == null) continue;
                    Semantico.Simbolo campo = miembro.getSimboloResuelto();
                    SimboloIr ir = new SimboloIr("s" + simbolos++, campo.nombre, TipoIr.desdeSemantico(campo.tipo), SimboloIr.Rol.CAMPO, c.nombre, origen(miembro));
                    campos.put(campo, ir); declarados.add(ir);
                }
                FirmaIr init = new FirmaIr(c.nombre, "init_IR", TipoIr.VOID, FirmaIr.Visibilidad.PRIVADA, Collections.emptyList());
                ClaseIr clase = new ClaseIr(c.nombre, base, declarados, false, init);
                clases.add(clase); clasesPorNombre.put(c.nombre, clase); nodosClases.put(c.nombre, n);
            }
            if (n.getNombre().equals("MIEMBRO") && hijo(n, "METODO") != null) {
                NodoArbolSintactico nodoMetodo = hijo(n, "METODO");
                Semantico.Simbolo m = nodoMetodo.getSimboloResuelto();
                cuerpos.put(m, hijo(nodoMetodo, "BLOQUE") == null ? hijo(n, "BLOQUE") : hijo(nodoMetodo, "BLOQUE"));
                List<TipoIr> tipos = new ArrayList<>();
                for (Semantico.Tipo t : m.parametros) tipos.add(TipoIr.desdeSemantico(t));
                firmas.put(m, new FirmaIr(m.propietaria.simbolo.nombre, m.nombre, TipoIr.desdeSemantico(m.tipo),
                        m.visibilidad == Semantico.Visibilidad.PUBLICO ? FirmaIr.Visibilidad.PUBLICA : FirmaIr.Visibilidad.PRIVADA, tipos));
                metodos.add(m);
            }
            for (NodoArbolSintactico h : n.getHijos()) recoger(h);
        }
        ResultadoGeneracionIr generar(String entrada) {
            Semantico.Simbolo consola = unidades.get(0).resultadoSemantico.getAmbitoGlobal().resolverTipo("Consola");
            if (consola != null && consola.consolaIntegrada) clases.add(new ClaseIr("Consola", null, Collections.emptyList(), true));
            for (Main.ResultadoAnalisis u : unidades) recoger(u.resultadoSemantico.getAstValidado());
            List<FirmaIr> orden = new ArrayList<>();
            for (Semantico.Simbolo m : metodos) orden.add(firmas.get(m));
            FirmaIr main = SeleccionEntradaIr.elegir(orden, entrada);
            List<FuncionIr> funciones = new ArrayList<>();
            for (Semantico.Simbolo m : metodos) funciones.add(new Funcion(m).generar());
            for (ClaseIr c : clases) if (c.inicializador != null) funciones.add(new Funcion(c.inicializador).generarInicializador(c));
            ModuloIr modulo = new ModuloIr("Proyecto", clases, funciones, main);
            ResultadoVerificacionIr verificacion = new VerificadorIr().verificar(modulo);
            if (!verificacion.esExitoso()) throw new Fallo("IR_INTERNA_INVALIDA: " + verificacion.getErrores());
            return new ResultadoGeneracionIr(modulo, Collections.emptyList());
        }
        final class Funcion {
            final Semantico.Simbolo metodo;
            final FirmaIr firma;
            final ValorIr receptor;
            final List<ValorIr> parametros = new ArrayList<>();
            final List<SimboloIr> locales = new ArrayList<>();
            final Map<Semantico.Simbolo, SimboloIr> ranuras = new IdentityHashMap<>();
            final List<ConstruccionBloque> bloques = new ArrayList<>();
            ConstruccionBloque actual;
            int temporales, etiquetas;
            Funcion(Semantico.Simbolo metodo) {
                this.metodo = metodo; firma = firmas.get(metodo);
                receptor = ValorIr.parametro("this", TipoIr.objeto(firma.propietaria)); actual = nuevo();
            }
            Funcion(FirmaIr firma) {
                metodo = null; this.firma = firma;
                receptor = ValorIr.parametro("this", TipoIr.objeto(firma.propietaria)); actual = nuevo();
            }
            FuncionIr generarInicializador(ClaseIr clase) {
                NodoArbolSintactico n = nodosClases.get(clase.nombre);
                if (clase.base != null) {
                    FirmaIr base = clasesPorNombre.get(clase.base).inicializador;
                    emitir(InstruccionIr.llamar(false, null, base, receptor, origen(n)));
                }
                for (NodoArbolSintactico miembro : hijo(n, "CUERPO_CLASE").getHijos()) {
                    if (hijo(miembro, "CAMPO") == null) continue;
                    NodoArbolSintactico init = hijo(miembro, "INICIALIZACION");
                    if (init != null) guardar(direccionCampo(campos.get(miembro.getSimboloResuelto()), receptor, miembro), expresion(init), miembro);
                }
                actual.fin = TerminadorIr.retorno(null, origen(n));
                return finalizar();
            }
            FuncionIr finalizar() {
                List<BloqueIr> salida = new ArrayList<>();
                for (ConstruccionBloque b : bloques) salida.add(new BloqueIr(b.id, b.instrucciones, b.fin));
                return new FuncionIr(firma, receptor, parametros, locales, salida);
            }
            ConstruccionBloque nuevo() {
                ConstruccionBloque b = new ConstruccionBloque("b" + etiquetas++); bloques.add(b); return b;
            }
            ValorIr temporal(TipoIr tipo) { return ValorIr.temporal("t" + temporales++, tipo); }
            void emitir(InstruccionIr i) { actual.instrucciones.add(i); }
            ValorIr op(InstruccionIr.Operacion op, TipoIr tipo, NodoArbolSintactico n, ValorIr... args) {
                ValorIr r = tipo.equals(TipoIr.VOID) ? null : temporal(tipo);
                emitir(InstruccionIr.operacion(op, r, origen(n), args)); return r;
            }
            SimboloIr reservar(Semantico.Simbolo s, NodoArbolSintactico n) {
                return ranuras.computeIfAbsent(s, clave -> reserva(s.nombre, TipoIr.desdeSemantico(s.tipo), n));
            }
            SimboloIr reserva(String nombre, TipoIr tipo, NodoArbolSintactico n) {
                SimboloIr s = new SimboloIr("s" + simbolos++, nombre, tipo, SimboloIr.Rol.LOCAL, firma.propietaria, origen(n)); locales.add(s); return s;
            }
            ValorIr direccion(SimboloIr s, NodoArbolSintactico n) {
                ValorIr d = temporal(TipoIr.direccion(s.tipo)); emitir(InstruccionIr.direccionLocal(d, s, origen(n))); return d;
            }
            ValorIr direccionCampo(SimboloIr campo, ValorIr rec, NodoArbolSintactico n) {
                ValorIr d = temporal(TipoIr.direccion(campo.tipo));
                emitir(InstruccionIr.direccionCampo(d, campo, rec, origen(n))); return d;
            }
            ValorIr direccion(NodoArbolSintactico n) {
                switch (n.getNombre()) {
                    case "IDENTIFICADOR":
                        Semantico.Simbolo s = n.getSimboloResuelto();
                        return s.rol == Semantico.RolSimbolo.CAMPO ? direccionCampo(campos.get(s), receptor, n) : direccion(reservar(s, n), n);
                    case "ACCESO_MIEMBRO": return direccionCampo(campos.get(n.getSimboloResuelto()), expresion(n.getHijosSemanticos().get(0)), n);
                    case "ACCESO_ARREGLO":
                        ValorIr lista = expresion(n.getHijosSemanticos().get(0)), indice = expresion(n.getHijosSemanticos().get(1));
                        return op(InstruccionIr.Operacion.DIRECCION_ELEMENTO, TipoIr.direccion(lista.tipo.elemento), n, lista, indice);
                    default: throw pendiente(n, "destino " + n.getNombre());
                }
            }
            ValorIr cargar(ValorIr d, NodoArbolSintactico n) { return op(InstruccionIr.Operacion.CARGAR, d.tipo.elemento, n, d); }
            void guardar(ValorIr d, ValorIr v, NodoArbolSintactico n) { op(InstruccionIr.Operacion.GUARDAR, TipoIr.VOID, n, d, v); }
            FuncionIr generar() {
                NodoArbolSintactico decl = metodo.nodoDeclaracion;
                NodoArbolSintactico ps = hijo(decl, "PARAMETROS");
                if (ps != null) for (NodoArbolSintactico p : ps.getHijosSemanticos()) {
                    SimboloIr s = reservar(p.getSimboloResuelto(), p);
                    ValorIr valor = ValorIr.parametro("p" + parametros.size(), s.tipo); parametros.add(valor);
                    guardar(direccion(s, p), valor, p);
                }
                sentencia(cuerpos.get(metodo));
                if (actual != null) {
                    if (!firma.retorno.equals(TipoIr.VOID)) throw new Fallo(origen(decl) + ": retorno requerido por contrato");
                    actual.fin = TerminadorIr.retorno(null, origen(decl));
                }
                return finalizar();
            }
            void sentencia(NodoArbolSintactico n) {
                if (actual == null) return;
                List<NodoArbolSintactico> hs = n.getHijosSemanticos();
                switch (n.getNombre()) {
                    case "BLOQUE": for (NodoArbolSintactico h : hs) sentencia(h); return;
                    case "DECLARACION":
                        SimboloIr s = reservar(n.getSimboloResuelto(), n);
                        NodoArbolSintactico init = hijo(n, "INICIALIZACION");
                        if (init != null) { ValorIr d = direccion(s, n); guardar(d, expresion(init), n); } return;
                    case "ASIGNACION":
                        ValorIr d = direccion(n.getHijos().get(0));
                        String operador = n.getHijos().get(1).getValor();
                        ValorIr anterior = operador.equals("=") ? null : convertir(cargar(d, n), TipoIr.desdeSemantico(n.getTipoOperacion()), n);
                        ValorIr valor = expresion(hs.get(2));
                        if (anterior != null) valor = binaria(operador.substring(0, 1), anterior, valor, n, anterior.tipo);
                        guardar(d, valor, n); return;
                    case "SENTENCIA_EXPRESION": expresion(hs.get(0)); return;
                    case "RETORNO":
                        ValorIr r = hs.isEmpty() ? null : expresion(hs.get(0));
                        actual.fin = TerminadorIr.retorno(r, origen(n)); actual = null; return;
                    case "CONDICIONAL": condicional(n); return;
                    case "CICLO":
                        if (hijo(n, "PALABRA_CLAVE") != null && "each".equals(hijo(n, "PALABRA_CLAVE").getValor())) each(n);
                        else ciclo(n); return;
                    case "EXIST": exist(n); return;
                    default: throw pendiente(n, "sentencia " + n.getNombre());
                }
            }
            void condicional(NodoArbolSintactico n) {
                ValorIr cond = expresion(n.getHijosSemanticos().get(0));
                ConstruccionBloque inicio = actual, si = nuevo(), no = nuevo();
                inicio.fin = TerminadorIr.condicional(cond, si.id, no.id, origen(n));
                actual = si; sentencia(hijo(n, "BLOQUE")); ConstruccionBloque finSi = actual;
                actual = no; NodoArbolSintactico otro = hijo(n, "ELSE");
                if (otro != null) sentencia(otro.getHijosSemanticos().get(0)); ConstruccionBloque finNo = actual;
                if (finSi == null && finNo == null) { actual = null; return; }
                actual = nuevo();
                if (finSi != null) finSi.fin = TerminadorIr.salto(actual.id, origen(n));
                if (finNo != null) finNo.fin = TerminadorIr.salto(actual.id, origen(n));
            }
            void ciclo(NodoArbolSintactico n) {
                ConstruccionBloque inicio = actual, prueba = nuevo(), cuerpo = nuevo(), fin = nuevo();
                inicio.fin = TerminadorIr.salto(prueba.id, origen(n)); actual = prueba;
                ValorIr cond = expresion(n.getHijosSemanticos().get(0));
                actual.fin = TerminadorIr.condicional(cond, cuerpo.id, fin.id, origen(n));
                actual = cuerpo; sentencia(hijo(n, "BLOQUE"));
                if (actual != null) actual.fin = TerminadorIr.salto(prueba.id, origen(n));
                actual = fin;
            }
            NodoArbolSintactico coleccion(NodoArbolSintactico n) {
                // La cabecera each ocupa marcador, tipo e identificador. Los nodos ELEMENTO_ITERADO
                // adicionales representan la promoción de lectura, nunca otra colección.
                return n.getHijosSemanticos().get(n.getNombre().equals("CICLO") ? 3 : 0);
            }
            void exist(NodoArbolSintactico n) {
                ValorIr lista = expresion(coleccion(n));
                ValorIr longitud = op(InstruccionIr.Operacion.LONGITUD_LISTA, TipoIr.INT, n, lista);
                ValorIr cond = op(InstruccionIr.Operacion.MAYOR, TipoIr.BOOL, n, longitud, ValorIr.entero(0));
                ConstruccionBloque inicio = actual, cuerpo = nuevo(), fin = nuevo();
                inicio.fin = TerminadorIr.condicional(cond, cuerpo.id, fin.id, origen(n));
                actual = cuerpo; sentencia(hijo(n, "BLOQUE"));
                if (actual != null) actual.fin = TerminadorIr.salto(fin.id, origen(n)); actual = fin;
            }
            void each(NodoArbolSintactico n) {
                ValorIr lista = expresion(coleccion(n));
                ValorIr longitud = op(InstruccionIr.Operacion.LONGITUD_LISTA, TipoIr.INT, n, lista);
                ValorIr indiceDir = direccion(reserva("indice", TipoIr.INT, n), n);
                ValorIr iteradorDir = direccion(reservar(n.getSimboloResuelto(), n), n);
                guardar(indiceDir, ValorIr.entero(0), n);
                ConstruccionBloque inicio = actual, prueba = nuevo(), cuerpo = nuevo(), fin = nuevo();
                inicio.fin = TerminadorIr.salto(prueba.id, origen(n)); actual = prueba;
                ValorIr indice = cargar(indiceDir, n);
                ValorIr cond = op(InstruccionIr.Operacion.MENOR, TipoIr.BOOL, n, indice, longitud);
                actual.fin = TerminadorIr.condicional(cond, cuerpo.id, fin.id, origen(n)); actual = cuerpo;
                ValorIr elemento = cargar(op(InstruccionIr.Operacion.DIRECCION_ELEMENTO, TipoIr.direccion(lista.tipo.elemento), n, lista, indice), n);
                guardar(iteradorDir, convertir(elemento, iteradorDir.tipo.elemento, n), n);
                sentencia(hijo(n, "BLOQUE"));
                if (actual != null) {
                    guardar(indiceDir, op(InstruccionIr.Operacion.SUMAR, TipoIr.INT, n, indice, ValorIr.entero(1)), n);
                    actual.fin = TerminadorIr.salto(prueba.id, origen(n));
                }
                actual = fin;
            }
            ValorIr convertir(ValorIr v, TipoIr destino, NodoArbolSintactico n) {
                if (v.tipo.equals(destino)) return v;
                InstruccionIr.Conversion conversion;
                if (v.tipo.equals(TipoIr.NULO) && destino.esReferencia()) conversion = InstruccionIr.Conversion.NULO_A_REFERENCIA;
                else if (destino.equals(TipoIr.STRING)) conversion = InstruccionIr.Conversion.A_STRING;
                else if (v.tipo.equals(TipoIr.INT) && destino.equals(TipoIr.FLOAT)) conversion = InstruccionIr.Conversion.INT_A_FLOAT;
                else if (v.tipo.categoria == TipoIr.Categoria.OBJETO && destino.categoria == TipoIr.Categoria.OBJETO) conversion = InstruccionIr.Conversion.SUBIR_CLASE;
                else throw pendiente(n, "conversión " + v.tipo + " a " + destino);
                ValorIr r = temporal(destino); emitir(InstruccionIr.convertir(r, conversion, v, origen(n))); return r;
            }
            TipoIr tipoLiteralLista(Semantico.Tipo t) {
                if (t.categoria == Semantico.CategoriaTipo.LISTA) {
                    Semantico.Tipo e = t.elemento;
                    // Literales efímeros []/[null] sin contexto solo permiten longitud, identidad o texto.
                    // Un slot string nullable resuelve su representación; ninguna lista mutable se convierte.
                    return TipoIr.lista(e.categoria == Semantico.CategoriaTipo.VACIO || e.categoria == Semantico.CategoriaTipo.NULL
                            ? TipoIr.STRING : e.categoria == Semantico.CategoriaTipo.LISTA ? tipoLiteralLista(e) : TipoIr.desdeSemantico(e));
                }
                return TipoIr.desdeSemantico(t);
            }
            ValorIr expresion(NodoArbolSintactico n) {
                List<NodoArbolSintactico> hs = n.getHijosSemanticos();
                switch (n.getNombre()) {
                    case "ENTERO":
                        try { return ValorIr.entero(ContratoEjecucion.literalEntero(n.getValor())); }
                        catch (IllegalArgumentException e) { throw new Fallo(origen(n) + ": LITERAL_FUERA_DE_RANGO: " + e.getMessage()); }
                    case "FLOAT":
                        try { return ValorIr.flotante(ContratoEjecucion.literalFlotante(n.getValor())); }
                        catch (IllegalArgumentException e) { throw new Fallo(origen(n) + ": LITERAL_FUERA_DE_RANGO: " + e.getMessage()); }
                    case "STRING": return ValorIr.cadena(n.getValor().substring(1, n.getValor().length() - 1));
                    case "PALABRA_CLAVE": return n.getValor().equals("null") ? ValorIr.nulo() : ValorIr.booleano(n.getValor().equals("true"));
                    case "IDENTIFICADOR": case "ACCESO_MIEMBRO": case "ACCESO_ARREGLO": return cargar(direccion(n), n);
                    case "AGRUPACION": case "INICIALIZACION": return expresion(hs.get(0));
                    case "CONVERSION_IMPLICITA": return convertir(expresion(hs.get(0)), tipoLiteralLista(n.getTipoSemantico()), n);
                    case "UNARIO":
                        if ("-".equals(n.getValor()) && hs.get(0).getNombre().equals("ENTERO")) {
                            try { return ValorIr.entero(ContratoEjecucion.literalEntero("-" + hs.get(0).getValor())); }
                            catch (IllegalArgumentException e) { throw new Fallo(origen(hs.get(0)) + ": LITERAL_FUERA_DE_RANGO: " + e.getMessage()); }
                        }
                        ValorIr v = expresion(hs.get(0));
                        return op(n.getValor().equals("not") ? InstruccionIr.Operacion.NO : InstruccionIr.Operacion.NEGAR, v.tipo, n, v);
                    case "EXPRESION":
                        if (n.getValor().equals("&&") || n.getValor().equals("||")) return cortocircuito(n);
                        ValorIr a = expresion(hs.get(0)), b = expresion(hs.get(1));
                        return binaria(n.getValor(), a, b, n, TipoIr.desdeSemantico(n.getTipoSemantico()));
                    case "LLAMADA": return llamada(n);
                    case "ARREGLO_LITERAL":
                        TipoIr tipoLista = tipoLiteralLista(n.getTipoSemantico());
                        List<ValorIr> elementos = new ArrayList<>();
                        for (NodoArbolSintactico h : hs) elementos.add(convertir(expresion(h), tipoLista.elemento, h));
                        return op(InstruccionIr.Operacion.CREAR_LISTA, tipoLista, n, elementos.toArray(new ValorIr[0]));
                    case "NEW":
                        TipoIr tipo = TipoIr.desdeSemantico(n.getTipoSemantico());
                        ValorIr instancia = op(InstruccionIr.Operacion.CREAR_OBJETO, tipo, n);
                        ClaseIr clase = clasesPorNombre.get(tipo.clase);
                        if (clase != null && clase.inicializador != null) emitir(InstruccionIr.llamar(false, null, clase.inicializador, instancia, origen(n)));
                        return instancia;
                    default: throw pendiente(n, "expresión " + n.getNombre());
                }
            }
            ValorIr binaria(String op, ValorIr a, ValorIr b, NodoArbolSintactico n, TipoIr tipo) {
                InstruccionIr.Operacion operacion;
                switch (op) {
                    case "+": operacion = tipo.equals(TipoIr.STRING) ? InstruccionIr.Operacion.CONCATENAR : InstruccionIr.Operacion.SUMAR; break;
                    case "-": operacion = InstruccionIr.Operacion.RESTAR; break;
                    case "*": operacion = InstruccionIr.Operacion.MULTIPLICAR; break;
                    case "/": operacion = InstruccionIr.Operacion.DIVIDIR; break;
                    case "==": operacion = InstruccionIr.Operacion.IGUAL; break;
                    case "!=": operacion = InstruccionIr.Operacion.DISTINTO; break;
                    case "<": operacion = InstruccionIr.Operacion.MENOR; break;
                    case "<=": operacion = InstruccionIr.Operacion.MENOR_IGUAL; break;
                    case ">": operacion = InstruccionIr.Operacion.MAYOR; break;
                    case ">=": operacion = InstruccionIr.Operacion.MAYOR_IGUAL; break;
                    default: throw pendiente(n, "operador " + op);
                }
                if (operacion == InstruccionIr.Operacion.CONCATENAR) {
                    a = aTexto(a, n); b = aTexto(b, n);
                }
                return op(operacion, tipo, n, a, b);
            }
            ValorIr aTexto(ValorIr valor, NodoArbolSintactico n) {
                ValorIr r = temporal(TipoIr.STRING);
                emitir(InstruccionIr.convertir(r, InstruccionIr.Conversion.A_STRING, valor, origen(n)));
                return r;
            }
            ValorIr cortocircuito(NodoArbolSintactico n) {
                // Ranura de unión sin phi: su dirección domina ambas ramas y la carga.
                ValorIr d = direccion(reserva("union", TipoIr.BOOL, n), n);
                ValorIr a = expresion(n.getHijosSemanticos().get(0));
                guardar(d, a, n);
                ConstruccionBloque inicio = actual, evaluar = nuevo(), fin = nuevo();
                boolean and = n.getValor().equals("&&");
                inicio.fin = TerminadorIr.condicional(a, and ? evaluar.id : fin.id, and ? fin.id : evaluar.id, origen(n));
                actual = evaluar; guardar(d, expresion(n.getHijosSemanticos().get(1)), n);
                actual.fin = TerminadorIr.salto(fin.id, origen(n)); actual = fin;
                return cargar(d, n);
            }
            ValorIr llamada(NodoArbolSintactico n) {
                Semantico.Simbolo s = n.getSimboloResuelto();
                ValorIr rec = n.getReceptor() == null ? receptor : expresion(n.getReceptor());
                List<ValorIr> args = new ArrayList<>();
                for (NodoArbolSintactico arg : hijo(n, "ARGUMENTOS").getHijosSemanticos()) args.add(expresion(arg));
                op(InstruccionIr.Operacion.COMPROBAR_NULO, TipoIr.VOID, n, rec);
                TipoIr retorno = TipoIr.desdeSemantico(s.tipo);
                ValorIr resultado = retorno.equals(TipoIr.VOID) ? null : temporal(retorno);
                if (s.runtime != null) {
                    if (s.runtime.imprime()) {
                        ValorIr texto = args.isEmpty() ? ValorIr.cadena("") : args.get(0);
                        // Incluso string puede ser null: A_STRING normaliza a texto "null".
                        ValorIr convertido = aTexto(texto, n);
                        emitir(InstruccionIr.runtime(s.runtime, resultado, origen(n), rec, convertido));
                    } else emitir(InstruccionIr.runtime(s.runtime, resultado, origen(n), rec));
                } else emitir(InstruccionIr.llamar(n.getDespacho() == NodoArbolSintactico.Despacho.VIRTUAL, resultado,
                        firmas.get(s), rec, origen(n), args.toArray(new ValorIr[0])));
                return resultado;
            }
        }
    }
    private static final class ConstruccionBloque {
        final String id;
        final List<InstruccionIr> instrucciones = new ArrayList<>();
        TerminadorIr fin;
        ConstruccionBloque(String id) { this.id = id; }
    }
}
