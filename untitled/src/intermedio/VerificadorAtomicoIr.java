import java.util.*;

/** Valida la frontera física del backend sin consultar el AST ni el IR anterior. */
final class VerificadorAtomicoIr {
    List<String> verificar(ModuloAtomicoIr modulo) { return new Sesion(modulo).verificar(); }
    private static final class Sesion {
        final ModuloAtomicoIr m;
        final List<String> errores = new ArrayList<>();
        final Map<String, FuncionAtomicaIr> funciones = new LinkedHashMap<>();
        FuncionAtomicaIr funcion;
        LayoutProgramaIr.Marco marco;
        int indice;
        final Map<String, String> tablas = new HashMap<>();
        final Map<String, FirmaIr> destinosVirtuales = new HashMap<>();
        final Map<String, ValorIr> receptoresTablas = new HashMap<>(), receptoresDestinos = new HashMap<>();
        final Map<String, CuadruploIr> definicionesReferencias = new HashMap<>();
        Sesion(ModuloAtomicoIr m) { this.m = Objects.requireNonNull(m); }
        void exige(boolean b, String codigo) { if (!b) errores.add(codigo + (funcion == null ? "" : " en " + funcion.firma.id() + " #" + indice)); }
        boolean ref(TipoIr t) { return t.esReferencia(); }
        TipoIr tipo(OperandoAtomico a) { return a != null && a.clase == OperandoAtomico.Clase.VALOR ? a.valor.tipo : null; }
        Long numero(OperandoAtomico a) {
            return a != null && a.clase == OperandoAtomico.Clase.VALOR && a.valor.clase == ValorIr.Clase.CONSTANTE && a.valor.tipo.equals(TipoIr.INT) ? (Long) a.valor.literal : null;
        }
        boolean etiqueta(OperandoAtomico a) { return a != null && a.clase == OperandoAtomico.Clase.ETIQUETA; }
        boolean valor(OperandoAtomico a) { return a != null && a.clase == OperandoAtomico.Clase.VALOR; }
        void verificarTipo(TipoIr t) {
            if (t.categoria == TipoIr.Categoria.OBJETO) exige(m.layout.clases.containsKey(t.clase), "CLASE_INEXISTENTE");
            if (t.elemento != null) verificarTipo(t.elemento);
        }
        List<String> verificar() {
            for (LayoutProgramaIr.Clase c : m.layout.clases.values()) {
                exige(c.tamano >= 16 && c.tamano % 8 == 0, "LAYOUT_CLASE");
                exige(c.tamano == LayoutProgramaIr.CABECERA_OBJETO + 8L * c.campos.size(), "TAMANO_CLASE_INCONSISTENTE");
                Set<Integer> offsets = new HashSet<>();
                for (Map.Entry<String, Integer> campo : c.campos.entrySet())
                    exige(campo.getValue() >= 16 && campo.getValue() < c.tamano && campo.getValue() % 8 == 0
                            && offsets.add(campo.getValue()) && c.tiposCampos.containsKey(campo.getKey()), "OFFSET_CAMPO");
                exige(c.campos.keySet().equals(c.tiposCampos.keySet()), "TIPOS_CAMPOS");
                for (TipoIr t : c.tiposCampos.values()) { exige(t.esAlmacenable(), "TIPO_CAMPO_INVALIDO"); verificarTipo(t); }
                exige(!c.consolaIntegrada || c.nombre.equals("Consola") && c.base == null && c.campos.isEmpty() && c.virtuales.isEmpty(), "LAYOUT_RUNTIME");
                if (c.base != null) {
                    LayoutProgramaIr.Clase base = m.layout.clases.get(c.base);
                    exige(base != null && base.tamano <= c.tamano, "LAYOUT_BASE");
                    exige(base == null || !base.consolaIntegrada, "HERENCIA_RUNTIME");
                    if (base != null) for (String id : base.campos.keySet())
                        exige(Objects.equals(base.campos.get(id), c.campos.get(id)) && Objects.equals(base.tiposCampos.get(id), c.tiposCampos.get(id)), "PREFIJO_BASE");
                }
            }
            for (FuncionAtomicaIr f : m.funciones) {
                exige(funciones.putIfAbsent(f.firma.id(), f) == null, "FUNCION_DUPLICADA");
                exige(m.layout.clases.containsKey(f.firma.propietaria), "CLASE_INEXISTENTE");
                verificarTipo(f.firma.retorno);
                for (TipoIr t : f.firma.parametros) verificarTipo(t);
            }
            for (Map.Entry<String, LayoutProgramaIr.Clase> item : m.layout.clases.entrySet()) {
                LayoutProgramaIr.Clase c = item.getValue(); exige(item.getKey().equals(c.nombre), "NOMBRE_LAYOUT_CLASE");
                if (c.inicializador != null) {
                    FirmaIr init = c.inicializador; FuncionAtomicaIr f = funciones.get(init.id());
                    exige(!c.consolaIntegrada && init.propietaria.equals(c.nombre) && init.nombre.equals("init_IR")
                            && init.visibilidad == FirmaIr.Visibilidad.PRIVADA && init.retorno.equals(TipoIr.VOID) && init.parametros.isEmpty()
                            && f != null && f.firma.mismoContrato(init), "INICIALIZADOR_INVALIDO");
                }
                Set<String> vistos = new HashSet<>(); String ancestro = c.nombre;
                while (ancestro != null && m.layout.clases.containsKey(ancestro)) {
                    if (!vistos.add(ancestro)) { exige(false, "HERENCIA_CICLICA"); break; }
                    ancestro = m.layout.clases.get(ancestro).base;
                }
                Set<String> firmas = new HashSet<>();
                for (FirmaIr virtual : c.virtuales) {
                    FuncionAtomicaIr f = funciones.get(virtual.id());
                    exige(f != null && f.firma.mismoContrato(virtual) && virtual.visibilidad == FirmaIr.Visibilidad.PUBLICA
                            && subtipo(c.nombre, virtual.propietaria) && firmas.add(LayoutProgramaIr.clave(virtual)), "SLOT_VIRTUAL_INVALIDO");
                }
                if (c.base != null && m.layout.clases.containsKey(c.base)) {
                    LayoutProgramaIr.Clase base = m.layout.clases.get(c.base);
                    exige(c.virtuales.size() >= base.virtuales.size(), "PREFIJO_VIRTUAL");
                    for (int i = 0; i < Math.min(c.virtuales.size(), base.virtuales.size()); i++)
                        exige(LayoutProgramaIr.clave(c.virtuales.get(i)).equals(LayoutProgramaIr.clave(base.virtuales.get(i)))
                                && copia(c.virtuales.get(i).retorno, base.virtuales.get(i).retorno), "PREFIJO_VIRTUAL");
                }
                verificarTablaVirtual(c);
            }
            exige(m.layout.marcos.keySet().equals(funciones.keySet()), "MARCOS_INCONSISTENTES");
            if (m.entrada != null) exige(funciones.containsKey(m.entrada.id()) && funciones.get(m.entrada.id()).firma.mismoContrato(m.entrada)
                    && SeleccionEntradaIr.esCandidata(m.entrada), "ENTRADA_INVALIDA");
            Set<String> nombres = new HashSet<>();
            for (FuncionAtomicaIr f : m.funciones) nombres.add(LayoutProgramaIr.nombreFuncion(f.firma));
            Set<String> nombresFunciones = new HashSet<>();
            for (FuncionAtomicaIr f : m.funciones) {
                exige(nombresFunciones.add(LayoutProgramaIr.nombreFuncion(f.firma)), "SIMBOLO_FUNCION_DUPLICADO");
                funcion = f; marco = m.layout.marcos.get(f.firma.id()); indice = -1;
                for (CuadruploIr q : f.codigo) if (q.operacion == CuadruploIr.Operacion.LABEL && etiqueta(q.destino))
                    exige(nombres.add(q.destino.etiqueta), "ETIQUETA_GLOBAL_DUPLICADA");
                if (marco == null) continue;
                exige(!m.layout.clases.containsKey(f.firma.propietaria) || !m.layout.clases.get(f.firma.propietaria).consolaIntegrada, "FUNCION_RUNTIME_INVALIDA");
                verificarMarco(); verificarCodigo();
            }
            return Collections.unmodifiableList(errores);
        }
        void verificarTablaVirtual(LayoutProgramaIr.Clase c) {
            LayoutProgramaIr.Clase base = c.base == null ? null : m.layout.clases.get(c.base);
            Map<String, FirmaIr> esperadas = new LinkedHashMap<>();
            if (base != null) for (FirmaIr f : base.virtuales) esperadas.put(LayoutProgramaIr.clave(f), f);
            for (FuncionAtomicaIr f : m.funciones) if (f.firma.propietaria.equals(c.nombre)) {
                String clave = LayoutProgramaIr.clave(f.firma); FirmaIr heredada = esperadas.get(clave);
                if (heredada != null) exige(f.firma.visibilidad == FirmaIr.Visibilidad.PUBLICA
                        && copia(f.firma.retorno, heredada.retorno), "SOBRESCRITURA_INVALIDA");
                if (f.firma.visibilidad != FirmaIr.Visibilidad.PUBLICA) continue;
                esperadas.put(clave, f.firma);
            }
            exige(c.virtuales.size() == esperadas.size(), "TABLA_VIRTUAL_INCONSISTENTE");
            for (FirmaIr f : c.virtuales) exige(esperadas.containsKey(LayoutProgramaIr.clave(f))
                    && f.mismoContrato(esperadas.get(LayoutProgramaIr.clave(f))), "TABLA_VIRTUAL_INCONSISTENTE");
        }
        void verificarMarco() {
            Set<Integer> offsets = new HashSet<>();
            for (Map<String, Integer> tabla : Arrays.asList(marco.valores, marco.locales))
                for (int offset : tabla.values()) exige(offset < 0 && offset % 8 == 0 && offset >= -marco.bytesValores && offsets.add(offset), "OFFSET_PILA");
            exige(marco.valores.keySet().equals(marco.tiposValores.keySet()) && marco.locales.keySet().equals(marco.tiposLocales.keySet()), "TIPOS_PILA");
            for (TipoIr t : marco.tiposValores.values()) verificarTipo(t);
            for (TipoIr t : marco.tiposLocales.values()) { exige(t.esAlmacenable(), "TIPO_LOCAL_INVALIDO"); verificarTipo(t); }
            exige(marco.bytesValores == offsets.size() * 8 && marco.maxArgumentos >= 0
                    && marco.bytesSalientes == m.layout.destino.bytesSalientes(marco.maxArgumentos)
                    && marco.tamano % 16 == 0 && marco.tamano >= marco.bytesValores + marco.bytesSalientes, "SOLAPAMIENTO_MARCO");
            exige(funcion.receptor.clase == ValorIr.Clase.PARAMETRO && funcion.receptor.tipo.equals(TipoIr.objeto(funcion.firma.propietaria))
                    && funcion.parametros.size() == funcion.firma.parametros.size(), "PARAMETROS_FUNCION");
            for (int i = 0; i < funcion.parametros.size() && i < funcion.firma.parametros.size(); i++)
                exige(funcion.parametros.get(i).clase == ValorIr.Clase.PARAMETRO
                        && funcion.parametros.get(i).tipo.equals(funcion.firma.parametros.get(i)), "PARAMETROS_FUNCION");
        }
        void operando(OperandoAtomico o) {
            if (o == null || o.clase != OperandoAtomico.Clase.VALOR) return;
            ValorIr v = o.valor;
            verificarTipo(v.tipo);
            if (v.clase != ValorIr.Clase.CONSTANTE) exige(marco.valores.containsKey(v.id) && v.tipo.equals(marco.tiposValores.get(v.id)), "VALOR_SIN_OFFSET");
        }
        boolean copia(TipoIr a, TipoIr r) {
            return a != null && r != null && (a.equals(r) || a.equals(TipoIr.NULO) && r.esReferencia()
                    || a.categoria == TipoIr.Categoria.OBJETO && r.categoria == TipoIr.Categoria.OBJETO && subtipo(a.clase, r.clase));
        }
        boolean subtipo(String clase, String base) {
            Set<String> vistos = new HashSet<>();
            while (clase != null && vistos.add(clase) && m.layout.clases.containsKey(clase)) {
                if (clase.equals(base)) return true; clase = m.layout.clases.get(clase).base;
            }
            return false;
        }
        String referenciaOriginal(ValorIr valor) {
            Set<String> vistos = new HashSet<>();
            while (valor.clase != ValorIr.Clase.CONSTANTE && vistos.add(valor.id)) {
                CuadruploIr q = definicionesReferencias.get(valor.id);
                if (q == null || q.operacion != CuadruploIr.Operacion.COPY || !valor(q.fuente1)
                        || q.fuente2 != null || valor.tipo.categoria != TipoIr.Categoria.OBJETO
                        || !copia(q.fuente1.valor.tipo, valor.tipo)) break;
                valor = q.fuente1.valor;
            }
            return valor.clase == ValorIr.Clase.CONSTANTE ? "#nulo" : valor.id;
        }
        void rastrearDestinosVirtuales(List<CuadruploIr> codigo) {
            // Los bloques pueden estar listados en un orden distinto al de ejecución.
            // Rastrear definiciones y copias hasta punto fijo evita depender del orden textual.
            definicionesReferencias.clear();
            for (CuadruploIr q : codigo) if (valor(q.destino)) definicionesReferencias.putIfAbsent(q.destino.valor.id, q);
            for (CuadruploIr q : codigo) if (q.operacion == CuadruploIr.Operacion.LOAD && valor(q.fuente1) && valor(q.destino)
                    && q.fuente1.valor.tipo.categoria == TipoIr.Categoria.OBJETO && Objects.equals(numero(q.fuente2), 0L)
                    && q.destino.valor.tipo.equals(TipoIr.direccion(TipoIr.INT)) && m.layout.clases.containsKey(q.fuente1.valor.tipo.clase)) {
                tablas.putIfAbsent(q.destino.valor.id, q.fuente1.valor.tipo.clase);
                receptoresTablas.putIfAbsent(q.destino.valor.id, q.fuente1.valor);
            }
            boolean cambio;
            do {
                int antes = tablas.size() + destinosVirtuales.size();
                for (CuadruploIr q : codigo) if (valor(q.fuente1) && valor(q.destino)) {
                    String fuente = q.fuente1.valor.id, destino = q.destino.valor.id;
                    if (q.operacion == CuadruploIr.Operacion.COPY && q.fuente2 == null && q.fuente1.valor.tipo.equals(q.destino.valor.tipo)) {
                        if (tablas.containsKey(fuente)) {
                            tablas.putIfAbsent(destino, tablas.get(fuente)); receptoresTablas.putIfAbsent(destino, receptoresTablas.get(fuente));
                        }
                        if (destinosVirtuales.containsKey(fuente)) {
                            destinosVirtuales.putIfAbsent(destino, destinosVirtuales.get(fuente)); receptoresDestinos.putIfAbsent(destino, receptoresDestinos.get(fuente));
                        }
                    } else if (q.operacion == CuadruploIr.Operacion.LOAD && tablas.containsKey(fuente)
                            && q.destino.valor.tipo.equals(TipoIr.direccion(TipoIr.INT))) {
                        Long offset = numero(q.fuente2); LayoutProgramaIr.Clase clase = m.layout.clases.get(tablas.get(fuente));
                        if (offset != null && offset >= 0 && offset % 8 == 0 && offset / 8 < clase.virtuales.size()) {
                            destinosVirtuales.putIfAbsent(destino, clase.virtuales.get((int) (offset / 8)));
                            receptoresDestinos.putIfAbsent(destino, receptoresTablas.get(fuente));
                        }
                    }
                }
                cambio = antes != tablas.size() + destinosVirtuales.size();
            } while (cambio);
        }
        void verificarCodigo() {
            tablas.clear(); destinosVirtuales.clear(); receptoresTablas.clear(); receptoresDestinos.clear();
            List<CuadruploIr> qs = funcion.codigo; Map<String, Integer> labels = new LinkedHashMap<>();
            rastrearDestinosVirtuales(qs);
            Map<String, Integer> defs = new HashMap<>(); List<ValorIr> parametros = new ArrayList<>();
            LlamadaAtomica retornoPendiente = null;
            List<LugarArgumentoIr> lugaresSalientes = new ArrayList<>();
            List<TipoIr> tiposEntrantes = new ArrayList<>(); tiposEntrantes.add(funcion.receptor.tipo); tiposEntrantes.addAll(funcion.firma.parametros);
            List<LugarArgumentoIr> lugaresEntrantes = m.layout.destino.argumentos(tiposEntrantes, true);
            int entradas = 0;
            for (indice = 0; indice < qs.size(); indice++) {
                CuadruploIr q = qs.get(indice); OperandoAtomico a = q.fuente1, b = q.fuente2, d = q.destino;
                operando(a); operando(b); operando(d);
                if (valor(d)) {
                    exige(d.valor.clase == (q.operacion == CuadruploIr.Operacion.READ_PARAM ? ValorIr.Clase.PARAMETRO : ValorIr.Clase.TEMPORAL), "DESTINO_INVALIDO");
                    exige(defs.putIfAbsent(d.valor.id, indice) == null, "VALOR_DUPLICADO");
                }
                if (retornoPendiente != null) exige(q.operacion == CuadruploIr.Operacion.GET_RETURN, "GET_RETURN_FALTANTE");
                if (!parametros.isEmpty()) exige(q.operacion == CuadruploIr.Operacion.PARAM || esLlamada(q), "PARAM_INTERRUMPIDO");
                TipoIr x = tipo(a), y = tipo(b), r = tipo(d); Long offset;
                boolean valido = false;
                switch (q.operacion) {
                    case LABEL:
                        valido = a == null && b == null && etiqueta(d);
                        if (etiqueta(d)) exige(labels.putIfAbsent(d.etiqueta, indice) == null, "LABEL_DUPLICADA"); break;
                    case JUMP: valido = etiqueta(a) && b == null && d == null; break;
                    case JUMP_IF_FALSE: valido = (TipoIr.BOOL.equals(x) || TipoIr.INT.equals(x)) && etiqueta(b) && d == null; break;
                    case READ_PARAM:
                        ValorIr esperado = entradas == 0 ? funcion.receptor : entradas <= funcion.parametros.size() ? funcion.parametros.get(entradas - 1) : null;
                        valido = esperado != null && a != null && a.clase == OperandoAtomico.Clase.ARGUMENTO && entradas < lugaresEntrantes.size()
                                && a.argumento.equals(lugaresEntrantes.get(entradas)) && b == null && valor(d)
                                && d.valor.id.equals(esperado.id) && d.valor.tipo.equals(esperado.tipo) && indice == entradas;
                        entradas++; break;
                    case LEA_LOCAL:
                        offset = numero(a); valido = offset != null && b == null && r != null && r.categoria == TipoIr.Categoria.DIRECCION;
                        if (valido) { String local = null; for (String id : marco.locales.keySet()) if (marco.locales.get(id).longValue() == offset) local = id;
                            valido = local != null && r.elemento.equals(marco.tiposLocales.get(local)); } break;
                    case LEA:
                        valido = x != null && TipoIr.INT.equals(y) && r != null && r.categoria == TipoIr.Categoria.DIRECCION;
                        if (valido) {
                            if (x.categoria == TipoIr.Categoria.OBJETO) valido = campo(x.clase, numero(b), r.elemento);
                            else if (x.categoria == TipoIr.Categoria.LISTA) valido = r.elemento.equals(x.elemento)
                                    && (numero(b) == null || numero(b) >= 16 && numero(b) % 8 == 0);
                            else valido = false;
                        } break;
                    case LOAD:
                        valido = x != null && numero(b) != null && r != null;
                        if (valido) {
                            if (x.categoria == TipoIr.Categoria.OBJETO) {
                                valido = numero(b) == 0 && r.equals(TipoIr.direccion(TipoIr.INT)) && m.layout.clases.containsKey(x.clase);
                            } else if (x.categoria == TipoIr.Categoria.LISTA) valido = numero(b) == 0 && r.equals(TipoIr.INT);
                            else if (x.categoria == TipoIr.Categoria.DIRECCION) {
                                String claseTabla = valor(a) ? tablas.get(a.valor.id) : null;
                                if (valor(a) && destinosVirtuales.containsKey(a.valor.id)) valido = false;
                                else if (claseTabla != null) {
                                    LayoutProgramaIr.Clase clase = m.layout.clases.get(claseTabla); long slot = numero(b) / 8;
                                    valido = numero(b) >= 0 && numero(b) % 8 == 0 && slot < clase.virtuales.size() && r.equals(TipoIr.direccion(TipoIr.INT));
                                } else valido = numero(b) == 0 && x.elemento.equals(r);
                            } else valido = false;
                        } break;
                    case STORE:
                        valido = x != null && x.categoria == TipoIr.Categoria.DIRECCION && x.elemento.equals(y) && d == null;
                        if (valor(a)) valido &= !tablas.containsKey(a.valor.id) && !destinosVirtuales.containsKey(a.valor.id);
                        break;
                    case COPY: valido = b == null && copia(x, r); break;
                    case ADD: case SUB: case MUL: valido = x != null && x.esNumerico() && x.equals(y) && x.equals(r); break;
                    case NEG: valido = x != null && x.esNumerico() && b == null && x.equals(r); break;
                    case NOT: valido = TipoIr.BOOL.equals(x) && b == null && TipoIr.BOOL.equals(r); break;
                    case EQ: case NE:
                        valido = x != null && y != null && TipoIr.BOOL.equals(r) && !x.equals(TipoIr.STRING) && !y.equals(TipoIr.STRING) && (x.equals(y) && (x.esAlmacenable() || x.equals(TipoIr.NULO)) || x.equals(TipoIr.NULO) && ref(y) || y.equals(TipoIr.NULO) && ref(x)); break;
                    case LT: case LE: case GT: case GE: valido = x != null && x.esNumerico() && x.equals(y) && TipoIr.BOOL.equals(r); break;
                    case INT_A_FLOAT: valido = TipoIr.INT.equals(x) && b == null && TipoIr.FLOAT.equals(r); break;
                    case PARAM:
                        valido = valor(a) && b != null && b.clase == OperandoAtomico.Clase.ARGUMENTO && b.argumento.indice == parametros.size() && !b.argumento.entrante && d == null;
                        if (valor(a)) parametros.add(a.valor);
                        if (b != null && b.clase == OperandoAtomico.Clase.ARGUMENTO) lugaresSalientes.add(b.argumento); break;
                    case CALL: case CALL_INDIRECT: case LLAMAR_RUNTIME:
                        valido = a != null && a.clase == OperandoAtomico.Clase.LLAMADA && numero(b) != null && d == null;
                        if (valido) {
                            LlamadaAtomica l = a.llamada;
                            valido = numero(b) == parametros.size() && l.parametros.size() == parametros.size() && parametros.size() <= marco.maxArgumentos && contrato(l, q.operacion)
                                    && lugaresSalientes.equals(m.layout.destino.argumentos(l.parametros, false));
                            for (int j = 0; j < parametros.size() && j < l.parametros.size(); j++) valido &= copia(parametros.get(j).tipo, l.parametros.get(j));
                            if (l.puntero != null) {
                                operando(OperandoAtomico.valor(l.puntero));
                                ValorIr receptor = receptoresDestinos.get(l.puntero.id);
                                boolean mismo = receptor != null && !parametros.isEmpty()
                                        && referenciaOriginal(receptor).equals(referenciaOriginal(parametros.get(0)));
                                exige(mismo, "RECEPTOR_VIRTUAL_INCONSISTENTE"); valido &= mismo;
                            }
                            retornoPendiente = l.retorno.equals(TipoIr.VOID) ? null : l;
                        }
                        parametros.clear(); lugaresSalientes.clear(); break;
                    case GET_RETURN:
                        valido = a == null && b == null && retornoPendiente != null && retornoPendiente.retorno.equals(r);
                        retornoPendiente = null; break;
                    case SET_RETURN:
                        valido = valor(a) && b == null && d == null && funcion.firma.retorno.equals(x)
                                && indice + 1 < qs.size() && qs.get(indice + 1).operacion == CuadruploIr.Operacion.RETURN; break;
                    case RETURN:
                        valido = a == null && b == null && d == null && (funcion.firma.retorno.equals(TipoIr.VOID)
                                || indice > 0 && qs.get(indice - 1).operacion == CuadruploIr.Operacion.SET_RETURN); break;
                    default: throw new AssertionError(q.operacion);
                }
                exige(valido, "CUADRUPLO_INVALIDO_" + q.operacion);
            }
            exige(entradas == funcion.parametros.size() + 1, "READ_PARAM_FALTANTE");
            exige(parametros.isEmpty(), "PARAM_SIN_CALL"); exige(retornoPendiente == null, "GET_RETURN_FALTANTE");
            for (CuadruploIr q : qs) {
                if (q.operacion == CuadruploIr.Operacion.JUMP && etiqueta(q.fuente1)) exige(labels.containsKey(q.fuente1.etiqueta), "SALTO_INEXISTENTE");
                if (q.operacion == CuadruploIr.Operacion.JUMP_IF_FALSE && etiqueta(q.fuente2)) exige(labels.containsKey(q.fuente2.etiqueta), "SALTO_INEXISTENTE");
            }
            verificarUsos(qs, labels, defs);
        }
        boolean campo(String clase, Long offset, TipoIr tipo) {
            LayoutProgramaIr.Clase c = m.layout.clases.get(clase); if (c == null || offset == null) return false;
            for (String id : c.campos.keySet()) if (c.campos.get(id).longValue() == offset && c.tiposCampos.get(id).equals(tipo)) return true;
            return false;
        }
        boolean esLlamada(CuadruploIr q) { return q.operacion == CuadruploIr.Operacion.CALL || q.operacion == CuadruploIr.Operacion.CALL_INDIRECT || q.operacion == CuadruploIr.Operacion.LLAMAR_RUNTIME; }
        boolean contrato(LlamadaAtomica l, CuadruploIr.Operacion op) {
            verificarTipo(l.retorno); for (TipoIr t : l.parametros) verificarTipo(t);
            if (l.puntero == null ? l.registroPuntero != null
                    : !ConvencionLlamadasIr.REGISTRO_DESTINO_INDIRECTO.equals(l.registroPuntero)
                        || m.layout.destino.argumentos(l.parametros, false).stream().anyMatch(a -> l.registroPuntero.equals(a.registro))) return false;
            if (l.firma != null) {
                FuncionAtomicaIr f = funciones.get(l.firma.id());
                LayoutProgramaIr.Clase c = m.layout.clases.get(l.firma.propietaria);
                boolean inicializador = c != null && c.inicializador != null && c.inicializador.mismoContrato(l.firma);
                return f != null && f.firma.mismoContrato(l.firma) && l.simbolo.equals(LayoutProgramaIr.nombreFuncion(l.firma))
                        && (op == CuadruploIr.Operacion.CALL_INDIRECT ? l.puntero != null && l.puntero.tipo.equals(TipoIr.direccion(TipoIr.INT))
                            && destinosVirtuales.containsKey(l.puntero.id) && LayoutProgramaIr.clave(destinosVirtuales.get(l.puntero.id)).equals(LayoutProgramaIr.clave(l.firma))
                            && l.firma.visibilidad == FirmaIr.Visibilidad.PUBLICA : op == CuadruploIr.Operacion.CALL && l.puntero == null
                            && l.firma.visibilidad == FirmaIr.Visibilidad.PRIVADA && (inicializador || l.firma.propietaria.equals(funcion.firma.propietaria)));
            }
            if (op != CuadruploIr.Operacion.LLAMAR_RUNTIME || l.puntero != null) return false;
            if (l.consola != null) {
                LayoutProgramaIr.Clase c = m.layout.clases.get("Consola"); LlamadaAtomica esperada = LlamadaAtomica.consola(l.consola);
                return c != null && c.consolaIntegrada && l.simbolo.equals(esperada.simbolo) && l.parametros.equals(esperada.parametros) && l.retorno.equals(esperada.retorno);
            }
            if (l.helper == null) return false;
            List<TipoIr> a = l.parametros; TipoIr r = l.retorno;
            if (!l.simbolo.equals(LlamadaAtomica.helper(l.helper, r, a).simbolo)) return false;
            switch (l.helper) {
                case COMPROBAR_NULO: return a.size() == 1 && ref(a.get(0)) && r.equals(TipoIr.VOID);
                case COMPROBAR_INDICE: return a.size() == 2 && a.get(0).categoria == TipoIr.Categoria.LISTA && a.get(1).equals(TipoIr.INT) && r.equals(TipoIr.VOID);
                case DIVIDIR_INT: return a.equals(Arrays.asList(TipoIr.INT, TipoIr.INT)) && r.equals(TipoIr.INT);
                case DIVIDIR_FLOAT: return a.equals(Arrays.asList(TipoIr.FLOAT, TipoIr.FLOAT)) && r.equals(TipoIr.FLOAT);
                case A_STRING: return a.size() == 1 && (a.get(0).esAlmacenable() || a.get(0).equals(TipoIr.NULO)) && r.equals(TipoIr.STRING);
                case CONCATENAR: return a.equals(Arrays.asList(TipoIr.STRING, TipoIr.STRING)) && r.equals(TipoIr.STRING);
                case IGUAL_STRING: return a.equals(Arrays.asList(TipoIr.STRING, TipoIr.STRING)) && r.equals(TipoIr.BOOL);
                case NUEVO_OBJETO: return a.isEmpty() && r.categoria == TipoIr.Categoria.OBJETO && m.layout.clases.containsKey(r.clase);
                case NUEVA_LISTA: return a.equals(Collections.singletonList(TipoIr.INT)) && r.categoria == TipoIr.Categoria.LISTA;
                default: throw new AssertionError(l.helper);
            }
        }
        void verificarUsos(List<CuadruploIr> qs, Map<String, Integer> labels, Map<String, Integer> defs) {
            int n = qs.size(); exige(n > 0, "FUNCION_VACIA"); if (n == 0) return;
            SortedSet<Integer> lideres = new TreeSet<>(); lideres.add(0); lideres.addAll(labels.values());
            for (int i = 0; i < n - 1; i++) {
                CuadruploIr.Operacion op = qs.get(i).operacion;
                if (op == CuadruploIr.Operacion.JUMP || op == CuadruploIr.Operacion.JUMP_IF_FALSE || op == CuadruploIr.Operacion.RETURN) lideres.add(i + 1);
            }
            List<Integer> inicios = new ArrayList<>(lideres); int bloques = inicios.size(); int[] bloqueDe = new int[n];
            for (int b = 0; b < bloques; b++) Arrays.fill(bloqueDe, inicios.get(b), b + 1 == bloques ? n : inicios.get(b + 1), b);
            List<Set<Integer>> pred = new ArrayList<>(), sucesores = new ArrayList<>();
            for (int b = 0; b < bloques; b++) { pred.add(new HashSet<>()); sucesores.add(new HashSet<>()); }
            for (int b = 0; b < bloques; b++) {
                int fin = (b + 1 == bloques ? n : inicios.get(b + 1)) - 1; CuadruploIr q = qs.get(fin);
                OperandoAtomico label = q.operacion == CuadruploIr.Operacion.JUMP ? q.fuente1 : q.operacion == CuadruploIr.Operacion.JUMP_IF_FALSE ? q.fuente2 : null;
                if (etiqueta(label) && labels.containsKey(label.etiqueta)) sucesores.get(b).add(bloqueDe[labels.get(label.etiqueta)]);
                if (q.operacion != CuadruploIr.Operacion.JUMP && q.operacion != CuadruploIr.Operacion.RETURN) {
                    exige(b + 1 < bloques, "CAIDA_FUERA_FUNCION"); if (b + 1 < bloques) sucesores.get(b).add(b + 1);
                }
                for (int s : sucesores.get(b)) pred.get(s).add(b);
            }
            BitSet alcanzables = new BitSet(); Deque<Integer> pendientes = new ArrayDeque<>(); pendientes.add(0);
            while (!pendientes.isEmpty()) { int b = pendientes.remove(); if (!alcanzables.get(b)) { alcanzables.set(b); pendientes.addAll(sucesores.get(b)); } }
            exige(alcanzables.cardinality() == bloques, "CODIGO_INALCANZABLE");
            BitSet[] dom = new BitSet[bloques];
            for (int b = 0; b < bloques; b++) { dom[b] = new BitSet(); if (b == 0) dom[b].set(0); else dom[b].or(alcanzables); }
            boolean cambio;
            do {
                cambio = false;
                for (int b = 1; b < bloques; b++) if (alcanzables.get(b)) {
                    BitSet nuevo = (BitSet) alcanzables.clone(); for (int p : pred.get(b)) if (alcanzables.get(p)) nuevo.and(dom[p]); nuevo.set(b);
                    if (!nuevo.equals(dom[b])) { dom[b] = nuevo; cambio = true; }
                }
            } while (cambio);
            for (indice = 0; indice < n; indice++) {
                CuadruploIr q = qs.get(indice); List<ValorIr> usos = new ArrayList<>();
                for (OperandoAtomico o : Arrays.asList(q.fuente1, q.fuente2)) {
                    if (valor(o)) usos.add(o.valor);
                    if (o != null && o.clase == OperandoAtomico.Clase.LLAMADA && o.llamada.puntero != null) usos.add(o.llamada.puntero);
                }
                for (ValorIr v : usos) if (v.clase != ValorIr.Clase.CONSTANTE) {
                    Integer def = defs.get(v.id);
                    if (def != null) {
                        ValorIr definido = qs.get(def).destino.valor;
                        exige(definido.clase == v.clase && definido.tipo.equals(v.tipo), "VALOR_INCONSISTENTE");
                    }
                    exige(def != null && (bloqueDe[def] == bloqueDe[indice] ? def < indice : dom[bloqueDe[indice]].get(bloqueDe[def])), "USO_SIN_DEFINICION");
                }
            }
        }
    }
}
