import java.util.*;

/** Verificación estructural y de tipos. No vuelve a analizar el lenguaje fuente. */
final class VerificadorIr {
    ResultadoVerificacionIr verificar(ModuloIr modulo) {
        Contexto c = new Contexto(Objects.requireNonNull(modulo));
        c.verificar();
        return new ResultadoVerificacionIr(c.errores);
    }

    private static final class Definicion {
        final ValorIr valor;
        final String bloque;
        final int indice;
        Definicion(ValorIr valor, String bloque, int indice) {
            this.valor = valor; this.bloque = bloque; this.indice = indice;
        }
    }

    private static final class Contexto {
        final ModuloIr modulo;
        final List<DiagnosticoIr> errores = new ArrayList<>();
        final Map<String, ClaseIr> clases = new LinkedHashMap<>();
        final Map<String, FuncionIr> funciones = new LinkedHashMap<>();
        final Set<String> simbolos = new HashSet<>();
        FuncionIr funcion;
        BloqueIr bloque;
        int indice = -1;
        OrigenIr origen = OrigenIr.GENERADO;
        Map<String, Definicion> definiciones;
        Map<String, Set<String>> dominadores;

        Contexto(ModuloIr modulo) { this.modulo = modulo; }
        void error(String codigo, String mensaje) {
            errores.add(new DiagnosticoIr(codigo, mensaje, funcion == null ? null : funcion.firma.id(),
                    bloque == null ? null : bloque.id, indice, origen));
        }
        void exigir(boolean condicion, String codigo, String mensaje) { if (!condicion) error(codigo, mensaje); }
        void verificar() {
            for (ClaseIr clase : modulo.clases) {
                exigir(clases.putIfAbsent(clase.nombre, clase) == null, "CLASE_DUPLICADA", clase.nombre);
                exigir(!Arrays.asList("int", "float", "bool", "string", "void", "null").contains(clase.nombre),
                        "NOMBRE_CLASE", "Nombre reservado de tipo: " + clase.nombre);
            }
            for (ClaseIr clase : modulo.clases) {
                exigir(!clase.consolaIntegrada || clase.nombre.equals("Consola") && clase.base == null && clase.campos.isEmpty(), "CLASE_RUNTIME_INVALIDA", clase.nombre);
                exigir(clase.base == null || !clases.containsKey(clase.base) || !clases.get(clase.base).consolaIntegrada, "HERENCIA_RUNTIME", clase.nombre);
                if (clase.base != null) exigir(clases.containsKey(clase.base), "BASE_INEXISTENTE", clase.base);
                Set<String> vistos = new HashSet<>();
                String actual = clase.nombre;
                while (actual != null && clases.containsKey(actual)) {
                    if (!vistos.add(actual)) { error("HERENCIA_CICLICA", clase.nombre); break; }
                    actual = clases.get(actual).base;
                }
                Set<String> nombres = new HashSet<>();
                for (SimboloIr campo : clase.campos) {
                    origen = campo.origen;
                    simbolo(campo);
                    exigir(campo.rol == SimboloIr.Rol.CAMPO && campo.propietaria.equals(clase.nombre),
                            "CAMPO_INVALIDO", campo.id);
                    exigir(nombres.add(campo.nombre), "CAMPO_DUPLICADO", campo.nombre);
                }
            }
            origen = OrigenIr.GENERADO;
            for (FuncionIr f : modulo.funciones) {
                exigir(funciones.putIfAbsent(f.firma.id(), f) == null, "FUNCION_DUPLICADA", f.firma.id());
                exigir(clases.containsKey(f.firma.propietaria), "CLASE_INEXISTENTE", f.firma.propietaria);
                exigir(!clases.containsKey(f.firma.propietaria) || !clases.get(f.firma.propietaria).consolaIntegrada,
                        "FUNCION_RUNTIME_INVALIDA", "Los intrínsecos no tienen cuerpos de usuario: " + f.firma.id());
                tipo(f.firma.retorno);
                for (TipoIr t : f.firma.parametros) tipo(t);
            }
            for (FuncionIr f : modulo.funciones) {
                verificarSobrescritura(f.firma);
                verificarFuncion(f);
            }
            for (ClaseIr c : modulo.clases) if (c.inicializador != null) {
                FirmaIr f = c.inicializador; FuncionIr cuerpo = funciones.get(f.id());
                exigir(!c.consolaIntegrada && f.propietaria.equals(c.nombre) && f.nombre.equals("init_IR")
                        && f.visibilidad == FirmaIr.Visibilidad.PRIVADA && f.retorno.equals(TipoIr.VOID) && f.parametros.isEmpty()
                        && cuerpo != null && cuerpo.firma.mismoContrato(f), "INICIALIZADOR_INVALIDO", c.nombre);
            }
            funcion = null; bloque = null; indice = -1; origen = OrigenIr.GENERADO;
            if (modulo.entrada != null) {
                FuncionIr f = funciones.get(modulo.entrada.id());
                exigir(f != null && f.firma.mismoContrato(modulo.entrada) && SeleccionEntradaIr.esCandidata(modulo.entrada),
                        "ENTRADA_INVALIDA", "Se requiere main público, void y sin argumentos declarado en el módulo");
            }
        }
        void tipo(TipoIr tipo) {
            if (tipo.categoria == TipoIr.Categoria.OBJETO)
                exigir(clases.containsKey(tipo.clase), "CLASE_INEXISTENTE", tipo.clase);
            if (tipo.elemento != null) tipo(tipo.elemento);
        }
        void simbolo(SimboloIr s) {
            exigir(simbolos.add(s.id), "SIMBOLO_DUPLICADO", s.id);
            tipo(s.tipo);
        }
        boolean subtipo(String derivada, String base) {
            Set<String> vistos = new HashSet<>();
            while (derivada != null && clases.containsKey(derivada) && vistos.add(derivada)) {
                if (derivada.equals(base)) return true;
                derivada = clases.get(derivada).base;
            }
            return false;
        }
        boolean receptor(TipoIr tipo, String propietaria) {
            return tipo.categoria == TipoIr.Categoria.OBJETO && subtipo(tipo.clase, propietaria);
        }
        void verificarSobrescritura(FirmaIr firma) {
            funcion = null; bloque = null; indice = -1; origen = OrigenIr.GENERADO;
            ClaseIr clase = clases.get(firma.propietaria);
            String base = clase == null ? null : clase.base;
            Set<String> vistos = new HashSet<>();
            while (base != null && clases.containsKey(base) && vistos.add(base)) {
                for (FuncionIr f : funciones.values()) {
                    FirmaIr heredada = f.firma;
                    if (!heredada.propietaria.equals(base) || heredada.visibilidad != FirmaIr.Visibilidad.PUBLICA
                            || !heredada.nombre.equals(firma.nombre) || !heredada.parametros.equals(firma.parametros)) continue;
                    boolean retorno = heredada.retorno.equals(firma.retorno)
                            || heredada.retorno.categoria == TipoIr.Categoria.OBJETO
                            && receptor(firma.retorno, heredada.retorno.clase);
                    exigir(retorno && firma.visibilidad == FirmaIr.Visibilidad.PUBLICA,
                            "SOBRESCRITURA_INVALIDA", firma.id());
                    return;
                }
                base = clases.get(base).base;
            }
        }
        void definir(ValorIr v, String b, int i) {
            tipo(v.tipo);
            exigir(definiciones.putIfAbsent(v.id, new Definicion(v, b, i)) == null,
                    "VALOR_DUPLICADO", "Identificador repetido: " + v.id);
        }
        void verificarFuncion(FuncionIr f) {
            funcion = f; bloque = null; indice = -1; origen = OrigenIr.GENERADO;
            definiciones = new LinkedHashMap<>();
            exigir(f.receptor.clase == ValorIr.Clase.PARAMETRO && f.receptor.tipo.equals(TipoIr.objeto(f.firma.propietaria)),
                    "RECEPTOR_INVALIDO", "El receptor debe ser parámetro de la clase declaradora");
            if (f.receptor.clase != ValorIr.Clase.CONSTANTE) definir(f.receptor, null, -1);
            exigir(f.parametros.size() == f.firma.parametros.size(), "PARAMETROS_INVALIDOS", "Cantidad incorrecta");
            for (int i = 0; i < f.parametros.size(); i++) {
                ValorIr p = f.parametros.get(i);
                exigir(p.clase == ValorIr.Clase.PARAMETRO && i < f.firma.parametros.size()
                        && p.tipo.equals(f.firma.parametros.get(i)), "PARAMETROS_INVALIDOS", "Parámetro " + i);
                if (p.clase != ValorIr.Clase.CONSTANTE) definir(p, null, -1);
            }
            for (SimboloIr local : f.locales) {
                origen = local.origen; simbolo(local);
                exigir(local.rol == SimboloIr.Rol.LOCAL && local.propietaria.equals(f.firma.propietaria), "LOCAL_INVALIDA", local.id);
            }
            origen = OrigenIr.GENERADO;
            Map<String, BloqueIr> bloques = new LinkedHashMap<>();
            exigir(!f.bloques.isEmpty(), "FUNCION_SIN_BLOQUES", f.firma.id());
            for (BloqueIr b : f.bloques) {
                bloque = b;
                exigir(bloques.putIfAbsent(b.id, b) == null, "BLOQUE_DUPLICADO", b.id);
                for (int i = 0; i < b.instrucciones.size(); i++) {
                    InstruccionIr ins = b.instrucciones.get(i);
                    indice = i; origen = ins.origen;
                    if (ins.resultado != null) {
                        exigir(ins.resultado.clase == ValorIr.Clase.TEMPORAL, "RESULTADO_INVALIDO", "Solo se definen temporales");
                        if (ins.resultado.clase != ValorIr.Clase.CONSTANTE) definir(ins.resultado, b.id, i);
                    }
                }
            }
            dominadores = calcularDominadores(bloques);
            for (BloqueIr b : f.bloques) {
                bloque = b;
                for (int i = 0; i < b.instrucciones.size(); i++) {
                    indice = i; InstruccionIr ins = b.instrucciones.get(i); origen = ins.origen;
                    for (ValorIr v : ins.operandos) uso(v);
                    instruccion(ins);
                }
                indice = b.instrucciones.size(); origen = b.terminador == null ? OrigenIr.GENERADO : b.terminador.origen;
                exigir(b.terminador != null, "SIN_TERMINADOR", b.id);
                if (b.terminador == null) continue;
                TerminadorIr t = b.terminador;
                if (t.valor != null) uso(t.valor);
                for (String destino : t.destinos) exigir(bloques.containsKey(destino), "SALTO_INEXISTENTE", destino);
                if (t.clase == TerminadorIr.Clase.CONDICIONAL)
                    exigir(t.valor.tipo.equals(TipoIr.BOOL), "CONDICION_INVALIDA", "La condición debe ser bool");
                if (t.clase == TerminadorIr.Clase.RETORNO)
                    exigir(f.firma.retorno.equals(TipoIr.VOID) ? t.valor == null : t.valor != null && t.valor.tipo.equals(f.firma.retorno),
                            "RETORNO_INVALIDO", "Retorno distinto de " + f.firma.retorno);
            }
        }
        Map<String, Set<String>> calcularDominadores(Map<String, BloqueIr> bloques) {
            Map<String, Set<String>> dom = new LinkedHashMap<>();
            if (bloques.isEmpty()) return dom;
            String entrada = bloques.keySet().iterator().next();
            Set<String> alcanzables = new LinkedHashSet<>();
            Deque<String> cola = new ArrayDeque<>(); cola.add(entrada);
            while (!cola.isEmpty()) {
                String id = cola.remove();
                if (!bloques.containsKey(id) || !alcanzables.add(id)) continue;
                TerminadorIr t = bloques.get(id).terminador;
                if (t != null) cola.addAll(t.destinos);
            }
            Map<String, Set<String>> previos = new HashMap<>();
            for (String id : alcanzables) {
                previos.put(id, new HashSet<>());
                dom.put(id, id.equals(entrada) ? new HashSet<>(Collections.singleton(id)) : new HashSet<>(alcanzables));
            }
            for (BloqueIr b : bloques.values()) {
                bloque = b; indice = -1; origen = OrigenIr.GENERADO;
                exigir(alcanzables.contains(b.id), "BLOQUE_INALCANZABLE", b.id);
                if (alcanzables.contains(b.id) && b.terminador != null)
                    for (String destino : b.terminador.destinos) if (previos.containsKey(destino)) previos.get(destino).add(b.id);
            }
            boolean cambio;
            do {
                cambio = false;
                for (String id : alcanzables) {
                    if (id.equals(entrada)) continue;
                    Set<String> nuevo = new HashSet<>(alcanzables);
                    for (String previo : previos.get(id)) nuevo.retainAll(dom.get(previo));
                    nuevo.add(id);
                    if (!nuevo.equals(dom.get(id))) { dom.put(id, nuevo); cambio = true; }
                }
            } while (cambio);
            return dom;
        }
        void uso(ValorIr v) {
            tipo(v.tipo);
            if (v.clase == ValorIr.Clase.CONSTANTE) return;
            Definicion d = definiciones.get(v.id);
            if (d == null) { error("VALOR_INDEFINIDO", v.id); return; }
            exigir(d.valor.tipo.equals(v.tipo) && d.valor.clase == v.clase, "VALOR_INCONSISTENTE", v.id);
            if (d.bloque != null) {
                boolean anterior = d.bloque.equals(bloque.id) ? d.indice < indice
                        : dominadores.getOrDefault(bloque.id, Collections.emptySet()).contains(d.bloque);
                exigir(anterior, "USO_SIN_DEFINICION", "La definición no domina el uso de " + v.id);
            }
        }
        void instruccion(InstruccionIr i) {
            TipoIr r = i.resultado == null ? null : i.resultado.tipo;
            List<ValorIr> a = i.operandos;
            boolean sinResultado = i.operacion == InstruccionIr.Operacion.GUARDAR
                    || i.operacion == InstruccionIr.Operacion.COMPROBAR_NULO || i.operacion == InstruccionIr.Operacion.COMPROBAR_INDICE
                    || (i.operacion == InstruccionIr.Operacion.LLAMAR_RUNTIME && i.runtime != null && i.runtime.imprime())
                    || ((i.operacion == InstruccionIr.Operacion.LLAMAR_DIRECTA || i.operacion == InstruccionIr.Operacion.LLAMAR_VIRTUAL)
                        && i.firma != null && i.firma.retorno.equals(TipoIr.VOID));
            exigir(sinResultado ? r == null : r != null, "RESULTADO_INVALIDO", "Resultado incompatible con " + i.operacion);
            boolean valido = false;
            switch (i.operacion) {
                case LLAMAR_RUNTIME:
                    ClaseIr consola = clases.get("Consola");
                    valido = i.runtime != null && consola != null && consola.consolaIntegrada
                            && a.size() == (i.runtime.imprime() ? 2 : 1)
                            && a.get(0).tipo.equals(TipoIr.objeto("Consola"))
                            && (i.runtime.imprime() ? r == null && a.get(1).tipo.equals(TipoIr.STRING) : i.runtime.retorno.equals(r));
                    break;
                case COPIAR: valido = a.size() == 1 && a.get(0).tipo.equals(r); break;
                case SUMAR: case RESTAR: case MULTIPLICAR: case DIVIDIR:
                    valido = a.size() == 2 && r != null && r.esNumerico() && a.get(0).tipo.equals(r) && a.get(1).tipo.equals(r); break;
                case NEGAR: valido = a.size() == 1 && r != null && r.esNumerico() && a.get(0).tipo.equals(r); break;
                case NO: valido = a.size() == 1 && TipoIr.BOOL.equals(r) && a.get(0).tipo.equals(TipoIr.BOOL); break;
                case MENOR: case MENOR_IGUAL: case MAYOR: case MAYOR_IGUAL:
                    valido = a.size() == 2 && TipoIr.BOOL.equals(r) && a.get(0).tipo.esNumerico() && a.get(0).tipo.equals(a.get(1).tipo); break;
                case IGUAL: case DISTINTO:
                    if (a.size() == 2 && TipoIr.BOOL.equals(r)) {
                        TipoIr x = a.get(0).tipo, y = a.get(1).tipo;
                        valido = x.equals(y) && (x.esAlmacenable() || x.equals(TipoIr.NULO))
                                || x.equals(TipoIr.NULO) && y.esReferencia() || y.equals(TipoIr.NULO) && x.esReferencia();
                    }
                    break;
                case DIRECCION_LOCAL:
                    valido = a.isEmpty() && i.simbolo != null && funcion.locales.contains(i.simbolo)
                            && i.simbolo.rol == SimboloIr.Rol.LOCAL && TipoIr.direccion(i.simbolo.tipo).equals(r); break;
                case DIRECCION_CAMPO:
                    if (a.size() == 1 && i.simbolo != null) {
                        ClaseIr propietaria = clases.get(i.simbolo.propietaria);
                        valido = propietaria != null && propietaria.campos.contains(i.simbolo) && i.simbolo.rol == SimboloIr.Rol.CAMPO
                                && receptor(a.get(0).tipo, i.simbolo.propietaria) && TipoIr.direccion(i.simbolo.tipo).equals(r);
                    }
                    break;
                case DIRECCION_ELEMENTO:
                    valido = a.size() == 2 && a.get(0).tipo.categoria == TipoIr.Categoria.LISTA && a.get(1).tipo.equals(TipoIr.INT)
                            && TipoIr.direccion(a.get(0).tipo.elemento).equals(r); break;
                case CARGAR:
                    valido = a.size() == 1 && a.get(0).tipo.categoria == TipoIr.Categoria.DIRECCION && a.get(0).tipo.elemento.equals(r); break;
                case GUARDAR:
                    valido = a.size() == 2 && a.get(0).tipo.categoria == TipoIr.Categoria.DIRECCION
                            && a.get(0).tipo.elemento.equals(a.get(1).tipo); break;
                case CONCATENAR:
                    valido = a.size() == 2 && TipoIr.STRING.equals(r) && a.get(0).tipo.equals(TipoIr.STRING) && a.get(1).tipo.equals(TipoIr.STRING); break;
                case CREAR_LISTA:
                    valido = r != null && r.categoria == TipoIr.Categoria.LISTA;
                    if (valido) for (ValorIr v : a) valido &= r.elemento.equals(v.tipo);
                    break;
                case LONGITUD_LISTA:
                    valido = a.size() == 1 && a.get(0).tipo.categoria == TipoIr.Categoria.LISTA && TipoIr.INT.equals(r); break;
                case CREAR_OBJETO: valido = a.isEmpty() && r != null && r.categoria == TipoIr.Categoria.OBJETO; break;
                case COMPROBAR_NULO: valido = a.size() == 1 && a.get(0).tipo.esReferencia(); break;
                case COMPROBAR_INDICE:
                    valido = a.size() == 2 && a.get(0).tipo.categoria == TipoIr.Categoria.LISTA && a.get(1).tipo.equals(TipoIr.INT); break;
                case CONVERTIR: valido = conversion(i, r); break;
                case LLAMAR_DIRECTA: case LLAMAR_VIRTUAL: valido = llamada(i, r); break;
                default: throw new AssertionError("Operación sin regla: " + i.operacion);
            }
            exigir(valido, "INSTRUCCION_INVALIDA", "Tipos, operandos o metadatos incorrectos para " + i.operacion);
        }
        boolean conversion(InstruccionIr i, TipoIr r) {
            if (i.operandos.size() != 1 || i.conversion == null || r == null) return false;
            TipoIr a = i.operandos.get(0).tipo;
            switch (i.conversion) {
                case INT_A_FLOAT: return a.equals(TipoIr.INT) && r.equals(TipoIr.FLOAT);
                case A_STRING: return (a.esAlmacenable() || a.equals(TipoIr.NULO)) && r.equals(TipoIr.STRING);
                case NULO_A_REFERENCIA: return a.equals(TipoIr.NULO) && r.esReferencia();
                case SUBIR_CLASE: return a.categoria == TipoIr.Categoria.OBJETO && r.categoria == TipoIr.Categoria.OBJETO
                        && subtipo(a.clase, r.clase);
                default: throw new AssertionError(i.conversion);
            }
        }
        boolean llamada(InstruccionIr i, TipoIr r) {
            if (i.firma == null || i.operandos.size() != i.firma.parametros.size() + 1) return false;
            FuncionIr destino = funciones.get(i.firma.id());
            if (destino == null || !destino.firma.mismoContrato(i.firma)) return false;
            boolean virtual = i.operacion == InstruccionIr.Operacion.LLAMAR_VIRTUAL;
            if (virtual != (i.firma.visibilidad == FirmaIr.Visibilidad.PUBLICA)) return false;
            ClaseIr propietaria = clases.get(i.firma.propietaria);
            boolean inicializador = propietaria != null && propietaria.inicializador != null && propietaria.inicializador.mismoContrato(i.firma);
            if (!virtual && !inicializador && !funcion.firma.propietaria.equals(i.firma.propietaria)) return false;
            if (!receptor(i.operandos.get(0).tipo, i.firma.propietaria)) return false;
            for (int n = 0; n < i.firma.parametros.size(); n++)
                if (!i.operandos.get(n + 1).tipo.equals(i.firma.parametros.get(n))) return false;
            return i.firma.retorno.equals(TipoIr.VOID) ? r == null : i.firma.retorno.equals(r);
        }
    }
}
