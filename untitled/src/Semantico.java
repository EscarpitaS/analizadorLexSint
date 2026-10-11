import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contrato semántico del lenguaje canónico.
 *
 * Registra declaraciones y después verifica nombres, tipos, accesibilidad y
 * flujo de inicialización y retorno sobre el AST del lenguaje canónico.
 */
final class Semantico {

    private final List<compiladorErrores> errores = new ArrayList<>();
    private final Map<String, ClaseInfo> clases = new LinkedHashMap<>();
    private Contexto contextoGlobal;
    private ResultadoSemantico resultado;
    private final Map<NodoArbolSintactico, String> origenes = new IdentityHashMap<>();
    private final Map<String, List<compiladorErrores>> erroresPorUnidad = new LinkedHashMap<>();
    private final Map<NodoArbolSintactico, ClaseInfo> declaracionesClase = new IdentityHashMap<>();
    private final Map<NodoArbolSintactico, Simbolo> declaracionesMiembro = new IdentityHashMap<>();

    enum CategoriaTipo {
        INT, FLOAT, STRING, BOOL, VOID, NULL, LISTA, CLASE, VACIO, ERROR
    }

    enum Mutabilidad {
        MUTABLE, INMUTABLE
    }

    enum Visibilidad {
        PUBLICO, PRIVADO
    }

    enum Ambito {
        GLOBAL, CLASE, METODO, BLOQUE
    }

    enum RolSimbolo {
        CLASE, CAMPO, METODO, CONSTRUCTOR, PARAMETRO, VARIABLE, ITERADOR
    }

    /** Resultado de flujo para decidir si un método puede terminar normalmente. */
    enum Flujo {
        CONTINUA, RETORNA
    }

    /** Representa un tipo primitivo, list&lt;T&gt; o una clase declarada. */
    static final class Tipo {
        final CategoriaTipo categoria;
        final String nombreClase;
        final Tipo elemento;

        private Tipo(CategoriaTipo categoria, String nombreClase, Tipo elemento) {
            this.categoria = categoria;
            this.nombreClase = nombreClase;
            this.elemento = elemento;
        }

        @Override
        public boolean equals(Object otro) {
            if (!(otro instanceof Tipo)) return false;
            Tipo tipo = (Tipo) otro;
            return categoria == tipo.categoria
                    && Objects.equals(nombreClase, tipo.nombreClase)
                    && Objects.equals(elemento, tipo.elemento);
        }

        @Override
        public int hashCode() {
            return Objects.hash(categoria, nombreClase, elemento);
        }

        @Override
        public String toString() {
            if (categoria == CategoriaTipo.LISTA) return "list<" + elemento + ">";
            return categoria == CategoriaTipo.CLASE ? nombreClase : categoria.name().toLowerCase();
        }
    }

    static final Tipo INT = nuevo(CategoriaTipo.INT);
    static final Tipo FLOAT = nuevo(CategoriaTipo.FLOAT);
    static final Tipo STRING = nuevo(CategoriaTipo.STRING);
    static final Tipo BOOL = nuevo(CategoriaTipo.BOOL);
    static final Tipo VOID = nuevo(CategoriaTipo.VOID);
    static final Tipo NULL = nuevo(CategoriaTipo.NULL);
    private static final Tipo VACIO = nuevo(CategoriaTipo.VACIO);
    /** Evita errores en cascada después de haber informado una incompatibilidad. */
    static final Tipo ERROR = nuevo(CategoriaTipo.ERROR);

    Semantico() {
        reiniciar();
    }

    /**
     * Primera pasada: registra todas las clases, valida su herencia y registra
     * campos y firmas de método, sin descender aún a los cuerpos de método.
     */
    void registrarDeclaraciones(NodoArbolSintactico arbol) {
        reiniciar();
        if (arbol == null) return;
        limpiarTipos(arbol);
        arbol.enlazarAmbito(contextoGlobal.ambitoActual);
        registrarConsola(arbol);

        List<NodoArbolSintactico> nodosClase = new ArrayList<>();
        recolectarClases(arbol, nodosClase);

        // 1. Todas las cabeceras primero: permite referencias hacia adelante.
        for (NodoArbolSintactico nodoClase : nodosClase) {
            registrarCabeceraClase(nodoClase);
        }

        // 2. Con el universo de clases completo, se valida la herencia.
        for (ClaseInfo clase : clases.values()) {
            validarHerencia(clase);
        }

        // 3. Finalmente se registran miembros; nunca se inspeccionan cuerpos.
        for (NodoArbolSintactico nodoClase : nodosClase) {
            ClaseInfo clase = declaracionesClase.get(nodoClase);
            if (clase != null) registrarMiembros(nodoClase, clase);
        }

        for (ClaseInfo clase : clases.values()) validarSobrescrituras(clase);
    }

    /** Ejecuta las dos pasadas disponibles: declaración y resolución de nombres. */
    ResultadoSemantico analizar(NodoArbolSintactico arbol) {
        registrarDeclaraciones(arbol);
        verificarCuerpos(arbol);
        if (arbol != null) {
            completarAmbitos(arbol, contextoGlobal.ambitoActual);
            decorarConversiones(arbol);
        }
        if (arbol != null && errores.isEmpty() && !arbol.contieneSintaxisIncompleta()) validarDecoracion(arbol);
        resultado = new ResultadoSemantico(arbol, contextoGlobal.ambitoActual, errores, true);
        return resultado;
    }

    ResultadoSemantico getResultado() { return resultado; }
    List<EntradaTablaSimbolos> getTablaSimbolos() {
        List<EntradaTablaSimbolos> filas = new ArrayList<>();
        recolectarTabla(contextoGlobal.ambitoActual, "global", filas);
        return Collections.unmodifiableList(filas);
    }
    private void recolectarTabla(AmbitoSimbolos ambito, String ruta, List<EntradaTablaSimbolos> filas) {
        for (Simbolo simbolo : ambito.getSimbolos()) filas.add(new EntradaTablaSimbolos(
                simbolo.nombre, simbolo.tipo.toString(), simbolo.rol + " @ " + ruta, simbolo.linea, simbolo.columna));
        for (int i = 0; i < ambito.getHijos().size(); i++) {
            AmbitoSimbolos hijo = ambito.getHijos().get(i);
            recolectarTabla(hijo, ruta + "/" + hijo.tipo + i, filas);
        }
    }

    AmbitoSimbolos getAmbitoGlobal() { return contextoGlobal.ambitoActual; }

    private void completarAmbitos(NodoArbolSintactico nodo, AmbitoSimbolos padre) {
        if (nodo.getAmbitoResuelto() == null) nodo.enlazarAmbito(padre);
        if (("TIPO".equals(nodo.getNombre()) || ("RETORNO".equals(nodo.getNombre()) && nodo.getHijos().isEmpty()))
                && nodo.getValor() != null) {
            Tipo tipo = nodo.getTipoSemantico();
            if (tipo != null) {
                nodo.anotarTipoSemantico(tipo);
                Tipo base = tipo;
                while (base.categoria == CategoriaTipo.LISTA) base = base.elemento;
                if (base.categoria == CategoriaTipo.CLASE && clases.containsKey(base.nombreClase))
                    nodo.enlazarSimbolo(clases.get(base.nombreClase).simbolo);
            }
        }
        for (NodoArbolSintactico hijo : nodo.getHijos()) completarAmbitos(hijo, nodo.getAmbitoResuelto());
    }

    /** Barrera de salida: no declarar exitoso un árbol con decoraciones esenciales ausentes. */
    private void validarDecoracion(NodoArbolSintactico nodo) {
        String nombre = nodo.getNombre();
        boolean expresion = java.util.Arrays.asList("ENTERO", "FLOAT", "STRING", "IDENTIFICADOR", "NEW", "LLAMADA",
                "ACCESO_MIEMBRO", "ACCESO_ARREGLO", "ARREGLO_LITERAL", "AGRUPACION", "UNARIO", "EXPRESION",
                "CONVERSION_IMPLICITA", "ELEMENTO_ITERADO").contains(nombre)
                || ("PALABRA_CLAVE".equals(nombre) && java.util.Arrays.asList("true", "false", "null").contains(nodo.getValor()));
        boolean referencia = java.util.Arrays.asList("IDENTIFICADOR", "LLAMADA", "NEW", "ACCESO_MIEMBRO", "PARAMETRO").contains(nombre);
        if (nodo.getAmbitoResuelto() == null || (expresion && (nodo.getTipoSemantico() == null || nodo.getTipoSemantico().equals(ERROR)))
                || (referencia && nodo.getSimboloResuelto() == null)) {
            error(nodo, "Decoración semántica incompleta en " + nombre + ".", "No se puede entregar este árbol al generador.");
        }
        for (NodoArbolSintactico hijo : nodo.getHijosSemanticos()) validarDecoracion(hijo);
    }

    private void convertir(NodoArbolSintactico padre, NodoArbolSintactico hijo, Tipo destino) {
        Tipo origen = hijo.getTipoSemantico();
        if (origen != null && destino != null && !origen.equals(ERROR) && !destino.equals(ERROR)
                && !origen.equals(destino) && !origen.equals(VOID) && !destino.equals(VOID)) {
            padre.convertirHijo(hijo, destino);
        }
    }

    private void decorarConversiones(NodoArbolSintactico nodo) {
        for (NodoArbolSintactico hijo : nodo.getHijos()) decorarConversiones(hijo);
        List<NodoArbolSintactico> h = nodo.getHijos();
        Tipo tipo = nodo.getTipoSemantico();
        Simbolo simbolo = nodo.getSimboloResuelto();
        if ("INICIALIZACION".equals(nodo.getNombre()) && h.size() == 1) {
            // El destino de la inicialización se aplica desde la declaración propietaria.
        } else if ("DECLARACION".equals(nodo.getNombre()) || "MIEMBRO".equals(nodo.getNombre())) {
            NodoArbolSintactico init = buscarHijo(nodo, "INICIALIZACION");
            if (simbolo != null && init != null && init.getHijos().size() == 1
                    && esAsignableConHerencia(simbolo.tipo, init.getTipoSemantico())) {
                convertir(init, init.getHijos().get(0), simbolo.tipo);
                init.anotarTipoSemantico(simbolo.tipo);
            }
        } else if ("RETORNO".equals(nodo.getNombre()) && simbolo != null && h.size() == 1) {
            if (esAsignableConHerencia(simbolo.tipo, h.get(0).getTipoSemantico())) convertir(nodo, h.get(0), simbolo.tipo);
        } else if ("ARREGLO_LITERAL".equals(nodo.getNombre()) && tipo != null && tipo.categoria == CategoriaTipo.LISTA) {
            for (NodoArbolSintactico hijo : h) convertir(nodo, hijo, tipo.elemento);
        } else if ("LLAMADA".equals(nodo.getNombre()) && simbolo != null) {
            NodoArbolSintactico args = buscarHijo(nodo, "ARGUMENTOS");
            if (args != null && simbolo.runtime == null) for (int i = 0; i < args.getHijos().size(); i++) convertir(args, args.getHijos().get(i), simbolo.parametros.get(i));
        } else if ("EXPRESION".equals(nodo.getNombre()) && h.size() == 2 && tipo != null && !tipo.equals(ERROR)) {
            Tipo a = h.get(0).getTipoSemantico(), b = h.get(1).getTipoSemantico();
            Tipo comun = null;
            if (esNumerico(a) && esNumerico(b)) comun = a.equals(FLOAT) || b.equals(FLOAT) ? FLOAT : INT;
            else if ("+".equals(nodo.getValor()) && tipo.equals(STRING)) comun = STRING;
            else if ("==".equals(nodo.getValor()) || "!=".equals(nodo.getValor())) comun = tipoComun(a, b);
            if (comun != null && !comun.equals(ERROR)) {
                nodo.anotarTipoOperacion(comun);
                convertir(nodo, h.get(0), comun); convertir(nodo, h.get(1), comun);
            }
        } else if ("CICLO".equals(nodo.getNombre()) && simbolo != null && nodo.getTipoOperacion() != null) {
            Tipo origen = nodo.getTipoOperacion();
            if (!origen.equals(simbolo.tipo) && esAsignableConHerencia(simbolo.tipo, origen)) {
                NodoArbolSintactico elemento = new NodoArbolSintactico("ELEMENTO_ITERADO", simbolo.nombre, nodo.getLinea(), nodo.getColumna());
                elemento.anotarTipoSemantico(origen);
                elemento.enlazarAmbito(nodo.getAmbitoResuelto());
                nodo.agregarHijoSemantico(elemento);
                nodo.convertirHijo(elemento, simbolo.tipo);
            }
        } else if ("ASIGNACION".equals(nodo.getNombre()) && h.size() == 3) {
            Tipo destino = h.get(0).getTipoSemantico(), valor = h.get(2).getTipoSemantico();
            if (destino == null || valor == null || destino.equals(ERROR) || valor.equals(ERROR)) return;
            String op = h.get(1).getValor();
            Tipo operacion = "=".equals(op) ? valor : "+=".equals(op) ? resultadoSuma(destino, valor) : resultadoAritmetico(destino, valor);
            nodo.anotarTipoOperacion(operacion);
            if (esAsignableConHerencia(destino, operacion) && !operacion.equals(ERROR)) {
                if ("=".equals(op)) convertir(nodo, h.get(2), destino);
                else { convertir(nodo, h.get(0), operacion); convertir(nodo, h.get(2), operacion); }
            }
        }
    }

    /** Analiza conjuntamente las unidades locales; conserva diagnósticos por archivo. */
    static Map<String, Semantico> analizarUnidades(Map<String, NodoArbolSintactico> unidades) {
        Semantico motor = new Semantico();
        NodoArbolSintactico conjunto = new NodoArbolSintactico("PROYECTO");
        for (Map.Entry<String, NodoArbolSintactico> unidad : unidades.entrySet()) {
            conjunto.agregar(unidad.getValue());
            motor.registrarOrigen(unidad.getValue(), unidad.getKey());
        }
        motor.analizar(conjunto);
        Map<String, Semantico> resultados = new LinkedHashMap<>();
        for (String origen : unidades.keySet()) {
            Semantico vista = new Semantico();
            vista.clases.putAll(motor.clases);
            vista.contextoGlobal = motor.contextoGlobal;
            vista.errores.addAll(motor.erroresPorUnidad.getOrDefault(origen, Collections.emptyList()));
            vista.resultado = new ResultadoSemantico(unidades.get(origen), motor.contextoGlobal.ambitoActual,
                    vista.errores, motor.resultado.esExitoso());
            resultados.put(origen, vista);
        }
        return resultados;
    }

    private void registrarOrigen(NodoArbolSintactico nodo, String origen) {
        origenes.put(nodo, origen);
        for (NodoArbolSintactico hijo : nodo.getHijos()) registrarOrigen(hijo, origen);
    }

    /**
     * Segunda pasada: verifica nombres, tipos y flujo dentro de los cuerpos.
     */
    void verificarCuerpos(NodoArbolSintactico arbol) {
        if (arbol == null) return;
        List<NodoArbolSintactico> nodosClase = new ArrayList<>();
        recolectarClases(arbol, nodosClase);
        for (NodoArbolSintactico nodoClase : nodosClase) {
            ClaseInfo clase = declaracionesClase.get(nodoClase);
            if (clase == null) continue; // cabecera duplicada o inválida
            verificarMiembros(nodoClase, contextoGlobal.conClase(clase));
        }
    }

    private void limpiarTipos(NodoArbolSintactico nodo) {
        nodo.limpiarSemantica();
        for (NodoArbolSintactico hijo : nodo.getHijos()) limpiarTipos(hijo);
    }

    private void reiniciar() {
        resultado = null;
        errores.clear();
        erroresPorUnidad.clear();
        clases.clear();
        declaracionesClase.clear();
        declaracionesMiembro.clear();
        contextoGlobal = Contexto.global();
    }

    private void recolectarClases(NodoArbolSintactico nodo, List<NodoArbolSintactico> resultado) {
        if ("CLASE".equals(nodo.getNombre())) {
            resultado.add(nodo);
            return;
        }
        for (NodoArbolSintactico hijo : nodo.getHijos()) {
            recolectarClases(hijo, resultado);
        }
    }

    private void registrarCabeceraClase(NodoArbolSintactico nodoClase) {
        if (omitir(nodoClase)) return;
        String nombre = nombreClase(nodoClase);
        if (nombre == null) {
            error(nodoClase, "Clase sin identificador.", "Declara un nombre después de 'class'.");
            return;
        }
        Simbolo simbolo = new Simbolo(nombre, clase(nombre), RolSimbolo.CLASE,
                visibilidadDe(nodoClase), Mutabilidad.INMUTABLE,
                nodoClase.getLinea(), nodoClase.getColumna(), Collections.emptyList());
        simbolo.origen = origenes.get(nodoClase);
        simbolo.nodoDeclaracion = nodoClase;
        if (!contextoGlobal.ambitoActual.declarar(simbolo)) {
            error(nodoClase, "La clase '" + nombre + "' ya fue declarada.",
                    "Usa un nombre de clase diferente.");
            return;
        }
        ClaseInfo info = new ClaseInfo(simbolo, contextoGlobal.ambitoActual, nombreBase(nodoClase));
        clases.put(nombre, info);
        declaracionesClase.put(nodoClase, info);
        nodoClase.anotarTipoSemantico(simbolo.tipo);
        nodoClase.enlazarSimbolo(simbolo);
        nodoClase.enlazarAmbito(info.miembros);
        buscarHijo(nodoClase, "IDENTIFICADOR").enlazarSimbolo(simbolo);
        buscarHijo(nodoClase, "IDENTIFICADOR").anotarTipoSemantico(simbolo.tipo);
        nodoClase.accion("Registrar clase " + nombre + (info.nombreBase == null ? "" : " extends " + info.nombreBase));
    }

    private void validarHerencia(ClaseInfo clase) {
        if (clase.nombreBase == null) return;
        if (clase.simbolo.nombre.equals(clase.nombreBase)) {
            error(clase.simbolo, "Una clase no puede extenderse a sí misma.",
                    "Elige una clase base diferente.");
            return;
        }
        ClaseInfo base = clases.get(clase.nombreBase);
        if (base != null && base.simbolo.consolaIntegrada) {
            error(clase.simbolo, "Consola integrada no admite herencia.", "Usa una instancia de Consola."); return;
        }
        if (base == null) {
            error(clase.simbolo, "No existe la clase base '" + clase.nombreBase + "'.",
                    "Declara o importa la clase antes de extenderla.");
            return;
        }
        clase.base = base;
        NodoArbolSintactico herencia = buscarHijo(clase.simbolo.nodoDeclaracion, "HERENCIA");
        if (herencia != null) {
            herencia.enlazarSimbolo(base.simbolo);
            NodoArbolSintactico id = buscarHijo(herencia, "IDENTIFICADOR");
            if (id != null) { id.enlazarSimbolo(base.simbolo); id.anotarTipoSemantico(base.simbolo.tipo); }
        }
        if (formaCicloHerencia(clase)) {
            error(clase.simbolo, "La herencia de '" + clase.simbolo.nombre + "' forma un ciclo.",
                    "Elimina una de las relaciones 'extends' del ciclo.");
            clase.base = null; // Evita ciclos en todos los recorridos posteriores.
        }
    }

    private boolean formaCicloHerencia(ClaseInfo inicio) {
        ClaseInfo actual = inicio.base;
        while (actual != null) {
            if (actual == inicio) return true;
            actual = actual.base;
        }
        return false;
    }

    /** Verifica métodos con misma firma en la jerarquía una vez registradas todas las firmas. */
    private void validarSobrescrituras(ClaseInfo clase) {
        if (clase.base == null) return;
        for (Simbolo metodo : clase.miembros.metodosLocales()) {
            Simbolo heredado = buscarMetodoHeredado(clase.base, metodo);
            if (heredado == null) continue;
            if (!(heredado.tipo.equals(metodo.tipo) ||
                    (heredado.tipo.categoria == CategoriaTipo.CLASE && metodo.tipo.categoria == CategoriaTipo.CLASE
                            && esAsignableConHerencia(heredado.tipo, metodo.tipo)))) {
                error(metodo, "El retorno de '" + metodo.nombre + "' no es compatible con el método heredado.",
                        "Conserva el tipo de retorno o usa un subtipo compatible.");
            }
            if (heredado.visibilidad == Visibilidad.PUBLICO && metodo.visibilidad == Visibilidad.PRIVADO) {
                error(metodo, "La sobrescritura de '" + metodo.nombre + "' no puede reducir visibilidad.",
                        "Declara el método como public.");
            }
        }
    }

    private Simbolo buscarMetodoHeredado(ClaseInfo clase, Simbolo metodo) {
        for (ClaseInfo actual = clase; actual != null; actual = actual.base) {
            for (Simbolo candidato : actual.miembros.resolverMetodosLocales(metodo.nombre)) {
                if (candidato.visibilidad == Visibilidad.PUBLICO
                        && candidato.parametros.equals(metodo.parametros)) return candidato;
            }
        }
        return null;
    }

    private void registrarMiembros(NodoArbolSintactico nodoClase, ClaseInfo clase) {
        NodoArbolSintactico cuerpo = buscarHijo(nodoClase, "CUERPO_CLASE");
        if (cuerpo == null) return;
        for (NodoArbolSintactico miembro : cuerpo.getHijos()) {
            if (!"MIEMBRO".equals(miembro.getNombre()) || omitir(miembro)) continue;
            NodoArbolSintactico campo = buscarHijo(miembro, "CAMPO");
            NodoArbolSintactico metodo = buscarHijo(miembro, "METODO");
            if (campo != null) registrarCampo(miembro, campo, clase);
            else if (metodo != null) registrarMetodo(miembro, metodo, clase);
        }
    }

    private void verificarMiembros(NodoArbolSintactico nodoClase, Contexto contextoClase) {
        NodoArbolSintactico cuerpo = buscarHijo(nodoClase, "CUERPO_CLASE");
        if (cuerpo == null) return;
        for (NodoArbolSintactico miembro : cuerpo.getHijos()) {
            if (!"MIEMBRO".equals(miembro.getNombre()) || omitir(miembro)) continue;
            NodoArbolSintactico campo = buscarHijo(miembro, "CAMPO");
            NodoArbolSintactico metodo = buscarHijo(miembro, "METODO");
            if (campo != null) verificarInicializadorCampo(miembro, contextoClase);
            else if (metodo != null) verificarMetodo(miembro, metodo, contextoClase);
        }
    }

    private void verificarInicializadorCampo(NodoArbolSintactico miembro, Contexto contextoClase) {
        NodoArbolSintactico nombre = buscarHijo(miembro, "IDENTIFICADOR");
        NodoArbolSintactico inicializacion = buscarHijo(miembro, "INICIALIZACION");
        if (nombre == null || inicializacion == null) return;
        Simbolo campo = contextoClase.ambitoActual.resolverValor(nombre.getValor());
        Tipo recibido = tipoEsperado(inicializacion, contextoClase, campo == null ? ERROR : campo.tipo);
        if (campo != null && !recibido.equals(ERROR) && !esAsignableConHerencia(campo.tipo, recibido)) {
            error(nombre, "No se puede inicializar el campo '" + campo.nombre + "' de tipo "
                    + campo.tipo + " con " + recibido + ".", "Usa una expresión compatible con el campo.");
        }
    }

    private void verificarMetodo(NodoArbolSintactico miembro, NodoArbolSintactico nodoMetodo,
                                 Contexto contextoClase) {
        Simbolo metodo = declaracionesMiembro.get(nodoMetodo);
        if (metodo == null) return; // la primera pasada ya informó una firma repetida o inválida

        Contexto contextoMetodo = contextoClase.conMetodo(metodo);
        nodoMetodo.enlazarAmbito(contextoMetodo.ambitoActual);
        NodoArbolSintactico parametros = buscarHijo(nodoMetodo, "PARAMETROS");
        if (parametros != null) {
            for (int i = 0; i < parametros.getHijos().size(); i++) {
                NodoArbolSintactico parametro = parametros.getHijos().get(i);
                String nombre = nombreParametro(parametro);
                Tipo tipo = i < metodo.parametros.size() ? metodo.parametros.get(i) : ERROR;
                if (nombre == null) continue;
                Simbolo simbolo = Simbolo.variable(nombre, tipo, RolSimbolo.PARAMETRO,
                        Mutabilidad.MUTABLE, parametro.getLinea(), parametro.getColumna());
                parametro.enlazarSimbolo(simbolo);
                parametro.anotarTipoSemantico(tipo);
                parametro.enlazarAmbito(contextoMetodo.ambitoActual);
                simbolo.nodoDeclaracion = parametro;
                if (contextoMetodo.ambitoActual.hayLocalVisible(nombre)
                        || !contextoMetodo.ambitoActual.declarar(simbolo)) {
                    error(parametro, "El parámetro '" + nombre + "' ya fue declarado.",
                            "Usa un nombre distinto para cada parámetro.");
                }
            }
        }
        // main conserva su bloque como hermano de METODO; los métodos tipados
        // lo contienen dentro de METODO.
        NodoArbolSintactico bloque = buscarHijo(nodoMetodo, "BLOQUE");
        if (bloque == null) bloque = buscarHijo(miembro, "BLOQUE");
        if (bloque != null) {
            visitarBloque(bloque, contextoMetodo);
            nodoMetodo.accion("Comprobar retorno declarado: " + metodo.tipo);
            if (!bloque.contieneSintaxisIncompleta() && !metodo.tipo.equals(VOID) && flujoDe(bloque) != Flujo.RETORNA) {
                error(nodoMetodo, "El método '" + metodo.nombre + "' puede terminar sin retornar un valor.",
                        "Agrega return en todos los caminos de ejecución.");
            }
        }
    }

    private String nombreParametro(NodoArbolSintactico parametro) {
        String texto = parametro.getValor();
        int separador = texto == null ? -1 : texto.lastIndexOf(' ');
        return separador <= 0 || separador == texto.length() - 1 ? null : texto.substring(separador + 1);
    }

    private void visitarBloque(NodoArbolSintactico bloque, Contexto contextoPadre) {
        bloque.accion("Abrir ámbito de bloque");
        Contexto contextoBloque = contextoPadre.conBloque();
        bloque.enlazarAmbito(contextoBloque.ambitoActual);
        boolean continua = true;
        Map<Simbolo, Boolean> salidaAlcanzable = null;
        boolean aviso = false;
        for (NodoArbolSintactico sentencia : bloque.getHijos()) {
            if (!continua) {
                sentencia.marcarInalcanzable();
                if (!aviso) {
                    error(sentencia, "Sentencia inalcanzable después de un retorno garantizado.",
                            "Elimina o mueve las sentencias posteriores al retorno.");
                    aviso = true;
                }
            }
            visitarSentencia(sentencia, contextoBloque);
            if (continua && flujoSentencia(sentencia) == Flujo.RETORNA) {
                continua = false;
                salidaAlcanzable = contextoBloque.ambitoActual.estadoInicializacion();
            }
        }
        if (salidaAlcanzable != null) contextoBloque.ambitoActual.restaurarInicializacion(salidaAlcanzable);
    }

    private Flujo flujoSentencia(NodoArbolSintactico sentencia) {
        NodoArbolSintactico temporal = new NodoArbolSintactico("BLOQUE");
        temporal.agregar(sentencia);
        return flujoDe(temporal);
    }

    private void visitarSentencia(NodoArbolSintactico sentencia, Contexto contexto) {
        sentencia.enlazarAmbito(contexto.ambitoActual);
        if (omitir(sentencia)) {
            // Conserva un nombre fiable como símbolo de error para evitar cascadas.
            if ("DECLARACION".equals(sentencia.getNombre())) {
                NodoArbolSintactico id = buscarHijo(sentencia, "IDENTIFICADOR");
                if (id != null && id.getValor() != null && id.getValor().matches("[a-zA-Z][a-zA-Z0-9]*")) {
                    Simbolo invalido = Simbolo.variable(id.getValor(), ERROR, RolSimbolo.VARIABLE,
                            Mutabilidad.MUTABLE, id.getLinea(), id.getColumna());
                    invalido.inicializado = true;
                    contexto.ambitoActual.declarar(invalido);
                    sentencia.accion("Reservar " + id.getValor() + " con tipo de error hasta corregir su declaración");
                }
            }
            return;
        }
        sentencia.accion("Comprobar " + sentencia.getNombre().toLowerCase(java.util.Locale.ROOT));
        switch (sentencia.getNombre()) {
            case "DECLARACION":
                visitarDeclaracion(sentencia, contexto);
                return;
            case "ASIGNACION":
                visitarAsignacion(sentencia, contexto);
                return;
            case "SENTENCIA_EXPRESION":
                for (NodoArbolSintactico expresion : sentencia.getHijos()) {
                    tipoDe(expresion, contexto);
                    if (!"LLAMADA".equals(expresion.getNombre())) {
                        error(expresion, "Una expresión aislada debe ser una llamada a método.",
                                "Usa el valor en una asignación o elimina la sentencia.");
                    }
                }
                return;
            case "CONDICIONAL":
                visitarControl(sentencia, contexto);
                return;
            case "CICLO":
                visitarCiclo(sentencia, contexto);
                return;
            case "EXIST":
                visitarExist(sentencia, contexto);
                return;
            case "RETORNO":
                sentencia.enlazarSimbolo(contexto.metodoActual);
                Tipo entregado = sentencia.getHijos().isEmpty() ? VOID
                        : tipoEsperado(sentencia.getHijos().get(0), contexto, contexto.tipoRetornoMetodo);
                if ((!sentencia.getHijos().isEmpty() && contexto.tipoRetornoMetodo.equals(VOID))
                        || !esAsignableConHerencia(contexto.tipoRetornoMetodo, entregado)) {
                    error(sentencia, "El retorno es " + entregado + " y el método declara " + contexto.tipoRetornoMetodo + ".",
                            "Devuelve una expresión compatible con el método.");
                }
                return;
            default:
                // Los nodos ERROR del parser no se reinterpretan como errores semánticos.
        }
    }

    private void visitarDeclaracion(NodoArbolSintactico declaracion, Contexto contexto) {
        NodoArbolSintactico tipoNodo = buscarHijo(declaracion, "TIPO");
        NodoArbolSintactico nombreNodo = buscarHijo(declaracion, "IDENTIFICADOR");
        if (tipoNodo == null || nombreNodo == null) return;
        String textoTipo = tipoNodo.getValor();
        Tipo tipo = "var".equals(textoTipo) || "let".equals(textoTipo)
                ? ERROR : tipoDesdeTexto(textoTipo, nombreNodo);
        Simbolo simbolo = Simbolo.variable(nombreNodo.getValor(), tipo, RolSimbolo.VARIABLE,
                mutabilidadDeDeclaracion(textoTipo), nombreNodo.getLinea(), nombreNodo.getColumna());
        if (contexto.ambitoActual.hayLocalVisible(simbolo.nombre)
                || !contexto.ambitoActual.declarar(simbolo)) {
            error(nombreNodo, "La variable '" + simbolo.nombre + "' ya fue declarada en un ámbito visible.",
                    "Usa un nombre diferente.");
        }

        // El nombre existe durante su inicializador, pero aún no puede leerse.
        NodoArbolSintactico inicializacion = buscarHijo(declaracion, "INICIALIZACION");
        Tipo tipoInicial = inicializacion == null ? ERROR : tipoEsperado(inicializacion, contexto, tipo);
        if ("var".equals(textoTipo) || "let".equals(textoTipo)) {
            if (tipoInicial.equals(ERROR)) {
                simbolo.tipo = ERROR;
            } else if (!esTipoInferible(tipoInicial)) {
                error(nombreNodo, "No se puede inferir el tipo de '" + simbolo.nombre + "'.",
                        "Inicializa var o let con una expresión de tipo conocido.");
            } else {
                simbolo.tipo = tipoInicial;
            }
        } else if (inicializacion != null && !esAsignableConHerencia(tipo, tipoInicial)) {
            error(nombreNodo, "No se puede asignar " + tipoInicial + " a " + tipo + ".",
                    "Usa una expresión compatible con el tipo declarado.");
        }
        simbolo.inicializado = inicializacion != null;
        nombreNodo.anotarTipoSemantico(simbolo.tipo);
        nombreNodo.enlazarSimbolo(simbolo);
        declaracion.enlazarSimbolo(simbolo);
        tipoNodo.anotarTipoSemantico(simbolo.tipo);
        simbolo.nodoDeclaracion = declaracion;
        declaracion.accion("Declarar " + simbolo.nombre + ": " + simbolo.tipo + "; "
                + (simbolo.inicializado ? "con inicializador" : "sin inicializar"));
    }

    private void visitarControl(NodoArbolSintactico control, Contexto contexto) {
        Map<Simbolo, Boolean> antes = contexto.ambitoActual.estadoInicializacion();
        NodoArbolSintactico bloqueThen = buscarHijo(control, "BLOQUE");
        NodoArbolSintactico nodoElse = buscarHijo(control, "ELSE");
        for (NodoArbolSintactico hijo : control.getHijos()) {
            if (!"BLOQUE".equals(hijo.getNombre()) && !"ELSE".equals(hijo.getNombre())) {
                Tipo tipo = tipoDe(hijo, contexto);
                if (!tipo.equals(ERROR) && !esCondicion(tipo)) {
                    error(hijo, "La condición debe ser bool, se obtuvo " + tipo + ".",
                            "Usa una expresión de comparación o lógica.");
                }
            }
        }
        if (bloqueThen != null) visitarBloque(bloqueThen, contexto);
        Map<Simbolo, Boolean> estadoThen = contexto.ambitoActual.estadoInicializacion();
        contexto.ambitoActual.restaurarInicializacion(antes);
        if (nodoElse != null) visitarElse(nodoElse, contexto);
        Map<Simbolo, Boolean> estadoElse = nodoElse == null ? antes : contexto.ambitoActual.estadoInicializacion();
        if (bloqueThen != null && flujoDe(bloqueThen) == Flujo.RETORNA) {
            contexto.ambitoActual.restaurarInicializacion(estadoElse);
        } else if (nodoElse != null && flujoElse(nodoElse) == Flujo.RETORNA) {
            contexto.ambitoActual.restaurarInicializacion(estadoThen);
        } else {
            contexto.ambitoActual.fusionarInicializacion(antes, estadoThen, estadoElse);
        }
    }

    private void visitarAsignacion(NodoArbolSintactico asignacion, Contexto contexto) {
        if (asignacion.getHijos().size() < 3) return;
        NodoArbolSintactico destino = asignacion.getHijos().get(0);
        String operador = asignacion.getHijos().get(1).getValor();
        Tipo tipoDestino;
        if ("IDENTIFICADOR".equals(destino.getNombre()) && "=".equals(operador)) {
            Simbolo simbolo = resolverIdentificador(destino, contexto, false);
            tipoDestino = simbolo == null ? ERROR : simbolo.tipo;
            destino.anotarTipoSemantico(tipoDestino);
        } else {
            tipoDestino = tipoDe(destino, contexto);
        }
        if (!"IDENTIFICADOR".equals(destino.getNombre()) && !"ACCESO_MIEMBRO".equals(destino.getNombre())
                && !"ACCESO_ARREGLO".equals(destino.getNombre())) {
            error(destino, "El destino de la asignación no es modificable.", "Asigna a una variable, campo o elemento de lista.");
        }
        Tipo tipoValor = tipoEsperado(asignacion.getHijos().get(2), contexto, tipoDestino);
        Tipo resultado = tipoValor;
        if (!"=".equals(operador)) {
            resultado = "+=".equals(operador) ? resultadoSuma(tipoDestino, tipoValor)
                    : resultadoAritmetico(tipoDestino, tipoValor);
            if (resultado.equals(ERROR) && !tipoDestino.equals(ERROR) && !tipoValor.equals(ERROR)) {
                error(asignacion, "El operador '" + operador + "' no admite " + tipoDestino + " y " + tipoValor + ".",
                        "Usa operandos numéricos; '+=' también permite string.");
            }
        }
        if (!tipoDestino.equals(ERROR) && !resultado.equals(ERROR)
                && !esAsignableConHerencia(tipoDestino, resultado)) {
            error(asignacion, "No se puede asignar " + resultado + " a " + tipoDestino + ".",
                    "Usa una expresión compatible con el destino.");
        }
        if ("IDENTIFICADOR".equals(destino.getNombre())) {
            Simbolo simbolo = buscarValor(destino.getValor(), contexto);
            if (simbolo != null && simbolo.mutabilidad == Mutabilidad.INMUTABLE) {
                error(destino, "No se puede reasignar la constante '" + simbolo.nombre + "'.",
                        "Declárala con var si su valor debe cambiar.");
            }
            if (simbolo != null) simbolo.inicializado = true;
        }
    }

    private void visitarElse(NodoArbolSintactico otro, Contexto contexto) {
        for (NodoArbolSintactico hijo : otro.getHijos()) {
            if ("BLOQUE".equals(hijo.getNombre())) visitarBloque(hijo, contexto);
            else if ("CONDICIONAL".equals(hijo.getNombre())) visitarControl(hijo, contexto);
        }
    }

    private void visitarCiclo(NodoArbolSintactico ciclo, Contexto contexto) {
        Map<Simbolo, Boolean> antes = contexto.ambitoActual.estadoInicializacion();
        boolean esEach = false;
        for (NodoArbolSintactico hijo : ciclo.getHijos()) {
            if ("PALABRA_CLAVE".equals(hijo.getNombre()) && "each".equals(hijo.getValor())) esEach = true;
        }
        if (!esEach) {
            visitarControl(ciclo, contexto);
            contexto.ambitoActual.restaurarInicializacion(antes);
            return;
        }
        NodoArbolSintactico tipoNodo = buscarHijo(ciclo, "TIPO");
        NodoArbolSintactico idNodo = buscarHijo(ciclo, "IDENTIFICADOR");
        NodoArbolSintactico bloque = buscarHijo(ciclo, "BLOQUE");
        Tipo tipoIterador = tipoNodo == null ? ERROR : tipoDesdeTexto(tipoNodo.getValor(), idNodo == null ? ciclo : idNodo);
        for (NodoArbolSintactico hijo : ciclo.getHijos()) {
            if (hijo != tipoNodo && hijo != idNodo && hijo != bloque && !"PALABRA_CLAVE".equals(hijo.getNombre())) {
                Tipo coleccion = tipoEsperado(hijo, contexto, listaDe(tipoIterador));
                if (coleccion.categoria == CategoriaTipo.LISTA) ciclo.anotarTipoOperacion(coleccion.elemento);
                if (!coleccion.equals(ERROR) && (coleccion.categoria != CategoriaTipo.LISTA
                        || !esAsignableConHerencia(tipoIterador, coleccion.elemento))) {
                    error(hijo, "repeat each requiere una lista compatible con el tipo del iterador.",
                            "Revisa la colección y el tipo del iterador.");
                }
            }
        }
        if (bloque != null) {
            Contexto contextoIterador = contexto.conBloque();
            if (tipoNodo != null && idNodo != null) {
                Simbolo iterador = Simbolo.variable(idNodo.getValor(), tipoIterador,
                        RolSimbolo.ITERADOR, Mutabilidad.MUTABLE, idNodo.getLinea(), idNodo.getColumna());
                iterador.inicializado = true;
                idNodo.enlazarSimbolo(iterador);
                idNodo.anotarTipoSemantico(tipoIterador);
                ciclo.enlazarSimbolo(iterador);
                ciclo.enlazarAmbito(contextoIterador.ambitoActual);
                tipoNodo.anotarTipoSemantico(tipoIterador);
                iterador.nodoDeclaracion = idNodo;
                if (contextoIterador.ambitoActual.hayLocalVisible(iterador.nombre)
                        || !contextoIterador.ambitoActual.declarar(iterador)) {
                    error(idNodo, "El iterador '" + iterador.nombre + "' ya fue declarado en un ámbito visible.",
                            "Usa otro nombre para el iterador.");
                }
            }
            visitarBloque(bloque, contextoIterador);
        }
        // repeat each puede iterar cero veces; sus asignaciones no son definitivas fuera del ciclo.
        contexto.ambitoActual.restaurarInicializacion(antes);
    }

    /** Exist(lista) ejecuta su bloque solo cuando hay elementos; puede no ejecutarse. */
    private void visitarExist(NodoArbolSintactico exist, Contexto contexto) {
        Map<Simbolo, Boolean> antes = contexto.ambitoActual.estadoInicializacion();
        NodoArbolSintactico bloque = buscarHijo(exist, "BLOQUE");
        for (NodoArbolSintactico hijo : exist.getHijos()) {
            if (!"BLOQUE".equals(hijo.getNombre())) {
                Tipo tipo = tipoDe(hijo, contexto);
                if (!tipo.equals(ERROR) && !esColeccionParaExist(tipo)) {
                    error(hijo, "Exist requiere list<T>, se obtuvo " + tipo + ".",
                            "Usa una lista como condición de Exist.");
                }
            }
        }
        if (bloque != null) visitarBloque(bloque, contexto);
        contexto.ambitoActual.restaurarInicializacion(antes);
    }

    /** Atributo sintetizado para las expresiones usadas por declaraciones y asignaciones. */
    private Tipo tipoDe(NodoArbolSintactico nodo, Contexto contexto) {
        nodo.enlazarAmbito(contexto.ambitoActual);
        if (omitir(nodo)) { nodo.anotarTipoSemantico(ERROR); return ERROR; }
        Tipo tipo = calcularTipo(nodo, contexto);
        nodo.anotarTipoSemantico(tipo);
        nodo.accion("Sintetizar tipo: " + tipo);
        if (tipo.categoria == CategoriaTipo.LISTA) anotarContextoLista(nodo, tipo);
        return tipo;
    }

    private Tipo calcularTipo(NodoArbolSintactico nodo, Contexto contexto) {
        if ("NEW".equals(nodo.getNombre())) return tipoConstruccion(nodo, contexto);
        if ("ENTERO".equals(nodo.getNombre())) return INT;
        if ("FLOAT".equals(nodo.getNombre())) return FLOAT;
        if ("STRING".equals(nodo.getNombre())) return STRING;
        if ("PALABRA_CLAVE".equals(nodo.getNombre())) {
            if ("true".equals(nodo.getValor()) || "false".equals(nodo.getValor())) return BOOL;
            if ("null".equals(nodo.getValor())) return NULL;
        }
        if ("IDENTIFICADOR".equals(nodo.getNombre())) {
            Simbolo simbolo = resolverIdentificador(nodo, contexto, true);
            return simbolo == null ? ERROR : simbolo.tipo;
        }
        if ("LLAMADA".equals(nodo.getNombre())) return tipoLlamada(nodo, contexto);
        if ("ACCESO_ARREGLO".equals(nodo.getNombre())) {
            if (nodo.getHijos().size() != 2) return ERROR;
            Tipo lista = tipoDe(nodo.getHijos().get(0), contexto);
            Tipo indice = tipoDe(nodo.getHijos().get(1), contexto);
            if (!indice.equals(ERROR) && !indice.equals(INT)) {
                error(nodo, "El índice de una lista debe ser int, se obtuvo " + indice + ".",
                        "Usa un entero como índice.");
            }
            if (lista.categoria != CategoriaTipo.LISTA) {
                if (!lista.equals(ERROR)) error(nodo, "El acceso con [] requiere list<T>, se obtuvo " + lista + ".",
                        "Usa un arreglo o lista como base del acceso.");
                return ERROR;
            }
            return lista.elemento;
        }
        if ("ACCESO_MIEMBRO".equals(nodo.getNombre())) {
            if (nodo.getHijos().isEmpty()) return ERROR;
            Tipo base = tipoDe(nodo.getHijos().get(0), contexto);
            Simbolo campo = resolverCampo(base, nodo.getValor());
            if (campo == null) {
                if (!base.equals(ERROR)) error(nodo, "No existe el campo '" + nodo.getValor() + "' en " + base + ".",
                        "Revisa el tipo del objeto y el nombre del miembro.");
                return ERROR;
            }
            if (!esAccesible(campo, contexto)) {
                error(nodo, "El campo '" + campo.nombre + "' es privado.",
                        "Accede al miembro desde su propia clase.");
                return ERROR;
            }
            nodo.enlazarSimbolo(campo);
            return campo.tipo;
        }
        if ("ARREGLO_LITERAL".equals(nodo.getNombre())) return tipoEsperado(nodo, contexto, ERROR);
        if ("AGRUPACION".equals(nodo.getNombre()) && !nodo.getHijos().isEmpty()) {
            return tipoDe(nodo.getHijos().get(0), contexto);
        }
        if ("UNARIO".equals(nodo.getNombre()) && !nodo.getHijos().isEmpty()) {
            Tipo operando = tipoDe(nodo.getHijos().get(0), contexto);
            if (operando.equals(ERROR)) return ERROR;
            boolean valido = "not".equals(nodo.getValor()) ? esCondicion(operando) : esNumerico(operando);
            if (!operando.equals(ERROR) && !valido) {
                error(nodo, "Operador unario incompatible con " + operando + ".", "Corrige el tipo del operando.");
            }
            return valido ? ("not".equals(nodo.getValor()) ? BOOL : operando) : ERROR;
        }
        if ("EXPRESION".equals(nodo.getNombre()) && nodo.getHijos().size() == 2) {
            Tipo izquierdo = tipoDe(nodo.getHijos().get(0), contexto);
            Tipo derecho = tipoDe(nodo.getHijos().get(1), contexto);
            String op = nodo.getValor();
            if (izquierdo.equals(ERROR) || derecho.equals(ERROR)) return ERROR;
            Tipo resultado = ERROR;
            if ("+".equals(op)) resultado = resultadoSuma(izquierdo, derecho);
            else if ("-".equals(op) || "*".equals(op) || "/".equals(op)) resultado = resultadoAritmetico(izquierdo, derecho);
            else if ("&&".equals(op) || "||".equals(op)) resultado = esCondicion(izquierdo) && esCondicion(derecho) ? BOOL : ERROR;
            else if ("<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
                resultado = admiteComparacionOrden(izquierdo, derecho) ? BOOL : ERROR;
            } else if ("==".equals(op) || "!=".equals(op)) {
                boolean compatibles = admiteIgualdad(izquierdo, derecho)
                        || (izquierdo.categoria == CategoriaTipo.CLASE && derecho.categoria == CategoriaTipo.CLASE
                        && (esAsignableConHerencia(izquierdo, derecho) || esAsignableConHerencia(derecho, izquierdo)));
                resultado = compatibles ? BOOL : ERROR;
            }
            if (resultado.equals(ERROR)) error(nodo, "El operador '" + op + "' no admite " + izquierdo + " y " + derecho + ".",
                    "Usa operandos compatibles con el operador.");
            return resultado;
        }
        Tipo ultimo = ERROR;
        for (NodoArbolSintactico hijo : nodo.getHijos()) ultimo = tipoDe(hijo, contexto);
        return ultimo;
    }

    /** Cada clase dispone únicamente de un constructor implícito sin parámetros. */
    private Tipo tipoConstruccion(NodoArbolSintactico nodo, Contexto contexto) {
        NodoArbolSintactico nombre = buscarHijo(nodo, "IDENTIFICADOR");
        NodoArbolSintactico argumentos = buscarHijo(nodo, "ARGUMENTOS");
        boolean valido = nombre != null;
        if (nombre != null && contexto.ambitoActual.resolverTipo(nombre.getValor()) == null) {
            error(nombre, "No existe la clase '" + nombre.getValor() + "'.",
                    "Declara o importa la clase antes de usar 'new'.");
            valido = false;
        }
        if (argumentos != null && !argumentos.getHijos().isEmpty()) {
            error(nodo, "El constructor implícito solo admite cero argumentos.",
                    "Usa new Clase(); no se admiten constructores con parámetros.");
            // Conserva los diagnósticos de las expresiones aunque la aridad sea inválida.
            for (NodoArbolSintactico argumento : argumentos.getHijos()) tipoDe(argumento, contexto);
            valido = false;
        }
        if (valido) {
            ClaseInfo construida = clases.get(nombre.getValor());
            nombre.enlazarSimbolo(construida.simbolo);
            nombre.anotarTipoSemantico(construida.simbolo.tipo);
            nodo.enlazarSimbolo(construida.constructor);
            nodo.enlazarConstruccion(construida);
            return construida.simbolo.tipo;
        }
        return ERROR;
    }

    private Tipo tipoLlamada(NodoArbolSintactico llamada, Contexto contexto) {
        if (llamada.getHijos().isEmpty()) return ERROR;
        NodoArbolSintactico destino = llamada.getHijos().get(0);
        String nombre = destino.getValor();
        ClaseInfo receptora = contexto.claseActual;
        boolean destinoValido = true;
        if ("ACCESO_MIEMBRO".equals(destino.getNombre()) && !destino.getHijos().isEmpty()) {
            Tipo base = tipoDe(destino.getHijos().get(0), contexto);
            receptora = base.categoria == CategoriaTipo.CLASE ? clases.get(base.nombreClase) : null;
            if (receptora == null) {
                if (!base.equals(ERROR)) error(destino, "La llamada requiere un objeto de clase.", "Revisa el receptor del método.");
                destinoValido = false;
            }
        } else if (!"IDENTIFICADOR".equals(destino.getNombre())) {
            tipoDe(destino, contexto);
            error(destino, "La expresión no designa un método invocable.", "Usa nombre(argumentos) u objeto.metodo(argumentos).");
            destinoValido = false;
        }
        if (destinoValido && receptora != null && receptora.simbolo.consolaIntegrada)
            return tipoLlamadaConsola(llamada, destino, receptora, contexto);
        List<Tipo> argumentos = new ArrayList<>();
        List<NodoArbolSintactico> nodosArgumentos = new ArrayList<>();
        for (int i = 1; i < llamada.getHijos().size(); i++) {
            for (NodoArbolSintactico argumento : llamada.getHijos().get(i).getHijos()) {
                nodosArgumentos.add(argumento);
                int antes = errores.size();
                prepararContexto(argumento, contexto);
                argumentos.add(errores.size() > antes || contieneErrorPreparado(argumento) ? ERROR : VOID);
            }
        }
        if (!destinoValido || argumentos.contains(ERROR)) return ERROR;
        List<Simbolo> compatibles = new ArrayList<>();
        for (Simbolo metodo : resolverMetodos(receptora, nombre)) {
            if (!esAccesible(metodo, contexto) || metodo.parametros.size() != argumentos.size()) continue;
            boolean coincide = true;
            for (int i = 0; i < argumentos.size(); i++) {
                if (!argumentoCompatible(nodosArgumentos.get(i), metodo.parametros.get(i))) coincide = false;
            }
            if (coincide) compatibles.add(metodo);
        }
        List<Simbolo> mejores = new ArrayList<>();
        for (Simbolo candidato : compatibles) {
            boolean dominado = false;
            for (Simbolo otro : compatibles) {
                if (otro != candidato && masEspecifico(otro, candidato)) dominado = true;
            }
            if (!dominado) mejores.add(candidato);
        }
        if (mejores.size() == 1) {
            Simbolo elegido = mejores.get(0);
            llamada.accion("Resolver llamada: " + elegido.propietaria.simbolo.nombre + "." + elegido.nombre
                    + elegido.parametros + " -> " + elegido.tipo);
            for (int i = 0; i < argumentos.size(); i++) {
                resolverContexto(nodosArgumentos.get(i), elegido.parametros.get(i), true);
            }
            destino.anotarTipoSemantico(elegido.tipo);
            destino.enlazarSimbolo(elegido);
            llamada.enlazarSimbolo(elegido);
            llamada.anotarReceptor(receptora.simbolo.tipo,
                    "ACCESO_MIEMBRO".equals(destino.getNombre()) ? destino.getHijos().get(0) : null);
            llamada.anotarDespacho(elegido.visibilidad == Visibilidad.PRIVADO
                    ? NodoArbolSintactico.Despacho.DIRECTO : NodoArbolSintactico.Despacho.VIRTUAL);
            return elegido.tipo;
        }
        error(destino, mejores.isEmpty() ? "No existe una firma accesible compatible para '" + nombre + "'."
                : "La llamada a '" + nombre + "' es ambigua.",
                "Revisa la visibilidad, cantidad y tipos de los argumentos.");
        return ERROR;
    }

    private void registrarConsola(NodoArbolSintactico arbol) {
        if (!importaConsola(arbol)) return;
        Simbolo tipo = new Simbolo("Consola", clase("Consola"), RolSimbolo.CLASE,
                Visibilidad.PUBLICO, Mutabilidad.INMUTABLE, 0, 0, Collections.emptyList());
        tipo.consolaIntegrada = true;
        contextoGlobal.ambitoActual.declarar(tipo);
        ClaseInfo info = new ClaseInfo(tipo, contextoGlobal.ambitoActual, null);
        clases.put("Consola", info);
    }
    private boolean importaConsola(NodoArbolSintactico nodo) {
        if ("IMPORTACION".equals(nodo.getNombre())) {
            NodoArbolSintactico ruta = buscarHijo(nodo, "RUTA");
            return ruta != null && "runtime.Consola".equals(ruta.getValor());
        }
        for (NodoArbolSintactico h : nodo.getHijos()) if (importaConsola(h)) return true;
        return false;
    }
    private boolean imprimible(Tipo tipo, boolean elemento) {
        if (tipo.equals(ERROR) || tipo.equals(VOID) || tipo.equals(VACIO)) return false;
        if (tipo.equals(NULL)) return !elemento;
        return tipo.categoria != CategoriaTipo.LISTA || imprimible(tipo.elemento, true);
    }
    private Tipo tipoLlamadaConsola(NodoArbolSintactico llamada, NodoArbolSintactico destino,
            ClaseInfo receptora, Contexto contexto) {
        RuntimeConsolaId id = RuntimeConsolaId.desdeNombre(destino.getValor());
        NodoArbolSintactico args = buscarHijo(llamada, "ARGUMENTOS");
        List<Tipo> tipos = new ArrayList<>();
        if (args != null) for (NodoArbolSintactico a : args.getHijos()) tipos.add(tipoDe(a, contexto));
        boolean aridad = id != null && (id == RuntimeConsolaId.CONSOLA_IMPRIMIR_LINEA
                ? tipos.size() <= 1 : tipos.size() == (id.imprime() ? 1 : 0));
        if (!aridad || id.imprime() && !tipos.isEmpty() && !imprimible(tipos.get(0), false)) {
            error(llamada, "Firma de Consola incompatible: " + destino.getValor() + ".",
                    "Comprueba nombre, aridad y valor imprimible."); return ERROR;
        }
        Simbolo simbolo = Simbolo.metodo(destino.getValor(), id.retornoSemantico(), Visibilidad.PUBLICO,
                tipos, llamada.getLinea(), llamada.getColumna());
        simbolo.runtime = id; simbolo.propietaria = receptora;
        llamada.enlazarSimbolo(simbolo); destino.enlazarSimbolo(simbolo); destino.anotarTipoSemantico(simbolo.tipo);
        llamada.anotarReceptor(receptora.simbolo.tipo, destino.getHijos().get(0));
        llamada.anotarDespacho(NodoArbolSintactico.Despacho.RUNTIME);
        return simbolo.tipo;
    }

    private boolean contieneErrorPreparado(NodoArbolSintactico nodo) {
        if (ERROR.equals(nodo.getTipoSemantico())) return true;
        for (NodoArbolSintactico hijo : nodo.getHijos()) if (contieneErrorPreparado(hijo)) return true;
        return false;
    }

    private boolean argumentoCompatible(NodoArbolSintactico nodo, Tipo esperado) {
        Tipo obtenido = resolverContexto(nodo, esperado, false);
        return !obtenido.equals(ERROR) && esAsignableConHerencia(esperado, obtenido);
    }

    private boolean masEspecifico(Simbolo a, Simbolo b) {
        boolean estricto = false;
        for (int i = 0; i < a.parametros.size(); i++) {
            Tipo ta = a.parametros.get(i), tb = b.parametros.get(i);
            if (!esAsignableConHerencia(tb, ta)) return false;
            estricto |= !ta.equals(tb);
        }
        return estricto;
    }

    private Simbolo resolverCampo(Tipo tipo, String nombre) {
        if (tipo.categoria != CategoriaTipo.CLASE) return null;
        for (ClaseInfo clase = clases.get(tipo.nombreClase); clase != null; clase = clase.base) {
            Simbolo campo = clase.miembros.resolverValor(nombre);
            if (campo != null && campo.rol == RolSimbolo.CAMPO) return campo;
        }
        return null;
    }

    private List<Simbolo> resolverMetodos(ClaseInfo clase, String nombre) {
        List<Simbolo> resultado = new ArrayList<>();
        for (ClaseInfo actual = clase; actual != null; actual = actual.base) {
            for (Simbolo metodo : actual.miembros.resolverMetodosLocales(nombre)) {
                if (actual != clase && metodo.visibilidad == Visibilidad.PRIVADO) continue;
                boolean oculto = false;
                for (Simbolo previo : resultado) if (previo.tieneMismaFirma(metodo)) oculto = true;
                if (!oculto) resultado.add(metodo);
            }
        }
        return resultado;
    }

    private boolean esAsignableConHerencia(Tipo destino, Tipo origen) {
        if (esAsignable(destino, origen)) return true;
        if (destino.categoria != CategoriaTipo.CLASE || origen.categoria != CategoriaTipo.CLASE) return false;
        ClaseInfo actual = clases.get(origen.nombreClase);
        while (actual != null) {
            if (destino.nombreClase.equals(actual.simbolo.nombre)) return true;
            actual = actual.base;
        }
        return false;
    }

    private boolean esAccesible(Simbolo simbolo, Contexto contexto) {
        return simbolo.propietaria == null || simbolo.visibilidad == Visibilidad.PUBLICO
                || simbolo.propietaria == contexto.claseActual;
    }

    private Simbolo buscarValor(String nombre, Contexto contexto) {
        Simbolo local = contexto.ambitoActual.resolverValor(nombre);
        if (local != null) return local;
        return contexto.claseActual == null ? null : resolverCampo(contexto.claseActual.simbolo.tipo, nombre);
    }

    private Simbolo resolverIdentificador(NodoArbolSintactico nodo, Contexto contexto, boolean lectura) {
        Simbolo simbolo = buscarValor(nodo.getValor(), contexto);
        if (simbolo == null) {
            error(nodo, "No existe el identificador '" + nodo.getValor() + "' en este ámbito.",
                    "Decláralo antes de usarlo o revisa su nombre.");
        } else if (!esAccesible(simbolo, contexto)) {
            error(nodo, "El campo '" + simbolo.nombre + "' es privado.", "Accede desde su clase declaradora.");
            return null;
        } else if (lectura && !simbolo.inicializado) {
            error(nodo, "La variable '" + nodo.getValor() + "' se usa antes de inicializarse.",
                    "Asigna un valor antes de leerla.");
        }
        if (simbolo != null) nodo.enlazarSimbolo(simbolo);
        if (simbolo != null) nodo.accion("Resolver " + simbolo.nombre + ": " + simbolo.tipo
                + " (" + simbolo.rol.toString().toLowerCase(java.util.Locale.ROOT) + ")");
        return simbolo;
    }

    private boolean esTipoInferible(Tipo tipo) {
        return !tipo.equals(NULL) && !tipo.equals(VOID) && !tipo.equals(VACIO) && !tipo.equals(ERROR)
                && (tipo.categoria != CategoriaTipo.LISTA || esTipoInferible(tipo.elemento));
    }

    /** Prepara hojas una sola vez; los literales esperan el contexto de su destino. */
    private void prepararContexto(NodoArbolSintactico nodo, Contexto contexto) {
        nodo.enlazarAmbito(contexto.ambitoActual);
        if (omitir(nodo)) { nodo.anotarTipoSemantico(ERROR); return; }
        if ("ARREGLO_LITERAL".equals(nodo.getNombre()) || "AGRUPACION".equals(nodo.getNombre())
                || "INICIALIZACION".equals(nodo.getNombre())) {
            for (NodoArbolSintactico hijo : nodo.getHijos()) prepararContexto(hijo, contexto);
        } else tipoDe(nodo, contexto);
    }

    private Tipo tipoEsperado(NodoArbolSintactico nodo, Contexto contexto, Tipo esperado) {
        prepararContexto(nodo, contexto);
        return resolverContexto(nodo, esperado, true);
    }

    /** Mismo algoritmo para inicialización, retorno y candidatos de llamada.
     * aplicar=false prueba una firma sin mutar anotaciones ni emitir diagnósticos. */
    private Tipo resolverContexto(NodoArbolSintactico nodo, Tipo esperado, boolean aplicar) {
        if (nodo.esSintaxisIncompleta()) return ERROR;
        Tipo obtenido;
        if (("INICIALIZACION".equals(nodo.getNombre()) || "AGRUPACION".equals(nodo.getNombre()))
                && nodo.getHijos().size() == 1) {
            obtenido = resolverContexto(nodo.getHijos().get(0), esperado, aplicar);
        } else if ("ARREGLO_LITERAL".equals(nodo.getNombre())) {
            boolean contextual = esperado.categoria == CategoriaTipo.LISTA;
            Tipo elemento = VACIO;
            boolean valido = true;
            for (NodoArbolSintactico hijo : nodo.getHijos()) {
                Tipo actual = resolverContexto(hijo, contextual ? esperado.elemento : ERROR, aplicar);
                if (actual.equals(ERROR)) valido = false;
                else if (contextual && !esAsignableConHerencia(esperado.elemento, actual)) {
                    if (aplicar) error(hijo, "Elemento incompatible: se esperaba " + esperado.elemento + " y se obtuvo " + actual + ".",
                            "Usa elementos compatibles con el tipo de la lista.");
                    valido = false;
                }
                if (!contextual) {
                    Tipo comun = tipoComun(elemento, actual);
                    if (comun.equals(ERROR) && !elemento.equals(ERROR) && !actual.equals(ERROR)) {
                        if (aplicar) error(hijo, "Los elementos de una lista deben tener tipos compatibles.", "Usa elementos del mismo tipo.");
                        valido = false;
                    }
                    elemento = comun;
                }
            }
            obtenido = valido ? (contextual ? esperado : listaDe(elemento)) : ERROR;
        } else obtenido = nodo.getTipoSemantico() == null ? ERROR : nodo.getTipoSemantico();
        if (aplicar) {
            nodo.anotarTipoSemantico(obtenido);
            nodo.accion(esperado.equals(ERROR) ? "Inferir tipo de la expresión" : "Comprobar compatibilidad con " + esperado);
            if (obtenido.categoria == CategoriaTipo.LISTA) anotarContextoLista(nodo, obtenido);
        }
        return obtenido;
    }

    private void anotarContextoLista(NodoArbolSintactico nodo, Tipo esperado) {
        if ("AGRUPACION".equals(nodo.getNombre()) && nodo.getHijos().size() == 1) {
            anotarContextoLista(nodo.getHijos().get(0), esperado);
            nodo.anotarTipoSemantico(nodo.getHijos().get(0).getTipoSemantico());
        } else if ("ARREGLO_LITERAL".equals(nodo.getNombre()) && esperado.categoria == CategoriaTipo.LISTA) {
            nodo.anotarTipoSemantico(esperado);
            for (NodoArbolSintactico hijo : nodo.getHijos()) anotarContextoLista(hijo, esperado.elemento);
        }
    }

    private Tipo tipoComun(Tipo a, Tipo b) {
        if (a.equals(ERROR) || b.equals(ERROR) || a.equals(VOID) || b.equals(VOID)) return ERROR;
        if (a.equals(VACIO)) return b;
        if (b.equals(VACIO)) return a;
        if (a.equals(b)) return a;
        if (esNumerico(a) && esNumerico(b)) return FLOAT;
        if (a.equals(NULL) && esReferencia(b)) return b;
        if (b.equals(NULL) && esReferencia(a)) return a;
        if (a.categoria == CategoriaTipo.CLASE && b.categoria == CategoriaTipo.CLASE) {
            for (ClaseInfo actual = clases.get(a.nombreClase); actual != null; actual = actual.base) {
                if (esAsignableConHerencia(actual.simbolo.tipo, b)) return actual.simbolo.tipo;
            }
        }
        if (a.categoria == CategoriaTipo.LISTA && b.categoria == CategoriaTipo.LISTA) {
            if (esAsignable(a, b)) return a;
            if (esAsignable(b, a)) return b;
        }
        return ERROR;
    }

    /** Los ciclos son conservadores: pueden ejecutarse cero veces y continúan. */
    private Flujo flujoDe(NodoArbolSintactico bloque) {
        for (NodoArbolSintactico sentencia : bloque.getHijos()) {
            if (sentencia.esSintaxisIncompleta()) continue;
            if ("RETORNO".equals(sentencia.getNombre())) return Flujo.RETORNA;
            if ("CONDICIONAL".equals(sentencia.getNombre())) {
                NodoArbolSintactico entonces = buscarHijo(sentencia, "BLOQUE");
                NodoArbolSintactico otro = buscarHijo(sentencia, "ELSE");
                if (entonces != null && otro != null
                        && flujoDe(entonces) == Flujo.RETORNA
                        && flujoElse(otro) == Flujo.RETORNA) return Flujo.RETORNA;
            }
        }
        return Flujo.CONTINUA;
    }

    private Flujo flujoElse(NodoArbolSintactico otro) {
        for (NodoArbolSintactico hijo : otro.getHijos()) {
            if ("BLOQUE".equals(hijo.getNombre())) return flujoDe(hijo);
            if ("CONDICIONAL".equals(hijo.getNombre())) {
                NodoArbolSintactico bloque = buscarHijo(hijo, "BLOQUE");
                NodoArbolSintactico elseAnidado = buscarHijo(hijo, "ELSE");
                return bloque != null && elseAnidado != null
                        && flujoDe(bloque) == Flujo.RETORNA
                        && flujoElse(elseAnidado) == Flujo.RETORNA
                        ? Flujo.RETORNA : Flujo.CONTINUA;
            }
        }
        return Flujo.CONTINUA;
    }

    private void registrarCampo(NodoArbolSintactico miembro, NodoArbolSintactico campo, ClaseInfo clase) {
        NodoArbolSintactico nombre = buscarHijo(miembro, "IDENTIFICADOR");
        if (nombre == null) return;
        Tipo tipo = tipoDesdeTexto(campo.getValor(), nombre);
        Simbolo simbolo = new Simbolo(nombre.getValor(), tipo, RolSimbolo.CAMPO,
                visibilidadDe(miembro), Mutabilidad.MUTABLE, nombre.getLinea(), nombre.getColumna(),
                Collections.emptyList());
        simbolo.propietaria = clase;
        simbolo.origen = clase.simbolo.origen;
        simbolo.nodoDeclaracion = miembro;
        if (clase.miembros.declarar(simbolo)) {
            declaracionesMiembro.put(campo, simbolo);
            campo.anotarTipoSemantico(tipo);
            campo.enlazarSimbolo(simbolo);
            nombre.enlazarSimbolo(simbolo);
            nombre.anotarTipoSemantico(tipo);
            miembro.enlazarSimbolo(simbolo);
            miembro.accion("Registrar campo " + simbolo.nombre + ": " + tipo);
        }
        else {
            error(nombre, "El campo '" + simbolo.nombre + "' ya fue declarado en '"
                    + clase.simbolo.nombre + "'.", "Usa otro nombre de campo.");
        }
    }

    private void registrarMetodo(NodoArbolSintactico miembro, NodoArbolSintactico metodo, ClaseInfo clase) {
        Tipo retorno = "main".equals(metodo.getValor()) ? VOID : tipoRetorno(metodo);
        List<Tipo> parametros = tiposParametros(metodo);
        Simbolo simbolo = Simbolo.metodo(metodo.getValor(), retorno, visibilidadDe(miembro),
                parametros, metodo.getLinea(), metodo.getColumna());
        simbolo.propietaria = clase;
        simbolo.origen = clase.simbolo.origen;
        simbolo.nodoDeclaracion = metodo;
        if (clase.miembros.declarar(simbolo)) {
            declaracionesMiembro.put(metodo, simbolo);
            metodo.anotarTipoSemantico(retorno);
            metodo.enlazarSimbolo(simbolo);
            NodoArbolSintactico tipoRetornoNodo = buscarHijo(metodo, "RETORNO");
            if (tipoRetornoNodo != null) tipoRetornoNodo.anotarTipoSemantico(retorno);
            metodo.accion("Registrar método " + simbolo.nombre + parametros + " -> " + retorno);
        }
        else {
            error(metodo, "El método '" + simbolo.nombre + "' ya tiene una firma igual en '"
                    + clase.simbolo.nombre + "'.", "Cambia los tipos de parámetros o el nombre del método.");
        }
    }

    private Tipo tipoRetorno(NodoArbolSintactico metodo) {
        NodoArbolSintactico retorno = buscarHijo(metodo, "RETORNO");
        return retorno == null ? ERROR : tipoDesdeTexto(retorno.getValor(), retorno);
    }

    private List<Tipo> tiposParametros(NodoArbolSintactico metodo) {
        NodoArbolSintactico parametros = buscarHijo(metodo, "PARAMETROS");
        if (parametros == null) return Collections.emptyList();
        List<Tipo> tipos = new ArrayList<>();
        for (NodoArbolSintactico parametro : parametros.getHijos()) {
            String texto = parametro.getValor();
            int separador = texto == null ? -1 : texto.lastIndexOf(' ');
            if (separador <= 0) {
                error(parametro, "Parámetro sin tipo o nombre.", "Usa la forma: Tipo nombre.");
                tipos.add(ERROR);
            } else {
                tipos.add(tipoDesdeTexto(texto.substring(0, separador), parametro));
            }
        }
        return tipos;
    }

    private Tipo tipoDesdeTexto(String texto, NodoArbolSintactico ubicacion) {
        if (texto == null) return ERROR;
        if ("int".equals(texto)) return INT;
        if ("float".equals(texto)) return FLOAT;
        if ("string".equals(texto)) return STRING;
        if ("bool".equals(texto)) return BOOL;
        if ("void".equals(texto)) return VOID;
        if (texto.startsWith("list<") && texto.endsWith(">")) {
            Tipo elemento = tipoDesdeTexto(texto.substring(5, texto.length() - 1), ubicacion);
            return listaDe(elemento);
        }
        if (!clases.containsKey(texto)) {
            error(ubicacion, "No existe el tipo '" + texto + "'.",
                    "Declara o importa la clase antes de usarla como tipo.");
            return ERROR;
        }
        return clase(texto);
    }

    private String nombreClase(NodoArbolSintactico nodoClase) {
        NodoArbolSintactico identificador = buscarHijo(nodoClase, "IDENTIFICADOR");
        return identificador == null ? null : identificador.getValor();
    }

    private String nombreBase(NodoArbolSintactico nodoClase) {
        NodoArbolSintactico herencia = buscarHijo(nodoClase, "HERENCIA");
        NodoArbolSintactico identificador = herencia == null ? null : buscarHijo(herencia, "IDENTIFICADOR");
        return identificador == null ? null : identificador.getValor();
    }

    private NodoArbolSintactico buscarHijo(NodoArbolSintactico nodo, String nombre) {
        for (NodoArbolSintactico hijo : nodo.getHijos()) {
            if (nombre.equals(hijo.getNombre())) return hijo;
        }
        return null;
    }

    private Visibilidad visibilidadDe(NodoArbolSintactico nodo) {
        for (NodoArbolSintactico hijo : nodo.getHijos()) {
            if ("PALABRA_CLAVE".equals(hijo.getNombre()) && "public".equals(hijo.getValor())) {
                return Visibilidad.PUBLICO;
            }
        }
        // La gramática permite omitir modificador; el contrato lo interpreta como privado.
        return Visibilidad.PRIVADO;
    }

    private boolean omitir(NodoArbolSintactico nodo) {
        if (!nodo.esSintaxisIncompleta()) return false;
        nodo.accion("Omitir validación de este fragmento: sintaxis incompleta");
        return true;
    }

    private void error(NodoArbolSintactico nodo, String mensaje, String pista) {
        nodo.registrarErrorSemantico(mensaje);
        nodo.accion("Error semántico: " + mensaje);
        registrarError(origenes.get(nodo), new compiladorErrores(compiladorErrores.TIPO_ERROR.SEMANTICO,
                nodo.getLinea(), nodo.getColumna(), nodo.getValor() == null ? nodo.getNombre() : nodo.getValor(),
                mensaje, pista));
    }

    private void error(Simbolo simbolo, String mensaje, String pista) {
        if (simbolo.nodoDeclaracion != null) {
            simbolo.nodoDeclaracion.registrarErrorSemantico(mensaje);
            simbolo.nodoDeclaracion.accion("Error semántico: " + mensaje);
        }
        registrarError(simbolo.origen, new compiladorErrores(compiladorErrores.TIPO_ERROR.SEMANTICO,
                simbolo.linea, simbolo.columna, simbolo.nombre, mensaje, pista));
    }

    private void registrarError(String origen, compiladorErrores error) {
        errores.add(error);
        if (origen != null) erroresPorUnidad.computeIfAbsent(origen, clave -> new ArrayList<>()).add(error);
    }

    List<compiladorErrores> getErrores() {
        return Collections.unmodifiableList(errores);
    }

    boolean hasErrors() { return !errores.isEmpty(); }

    ClaseInfo getClase(String nombre) { return clases.get(nombre); }

    private static Tipo nuevo(CategoriaTipo categoria) {
        return new Tipo(categoria, null, null);
    }

    static Tipo listaDe(Tipo elemento) {
        if (elemento == null || elemento.equals(VOID) || elemento.equals(ERROR)) return ERROR;
        return new Tipo(CategoriaTipo.LISTA, null, elemento);
    }

    static Tipo clase(String nombre) {
        return new Tipo(CategoriaTipo.CLASE, nombre, null);
    }

    /** Símbolo declarado; conserva información suficiente para las dos pasadas. */
    static final class Simbolo {
        final String nombre;
        RuntimeConsolaId runtime;
        boolean consolaIntegrada;
        Tipo tipo;
        final RolSimbolo rol;
        final Visibilidad visibilidad;
        final Mutabilidad mutabilidad;
        final int linea;
        final int columna;
        final List<Tipo> parametros;
        boolean inicializado;
        ClaseInfo propietaria;
        AmbitoSimbolos ambitoDeclaracion;
        String origen;
        NodoArbolSintactico nodoDeclaracion;

        Simbolo(String nombre, Tipo tipo, RolSimbolo rol, Visibilidad visibilidad,
                Mutabilidad mutabilidad, int linea, int columna, List<Tipo> parametros) {
            this.nombre = nombre;
            this.tipo = tipo;
            this.rol = rol;
            this.visibilidad = visibilidad;
            this.mutabilidad = mutabilidad;
            this.linea = linea;
            this.columna = columna;
            this.parametros = Collections.unmodifiableList(new ArrayList<>(parametros));
            this.inicializado = rol == RolSimbolo.CAMPO || rol == RolSimbolo.PARAMETRO;
        }

        static Simbolo variable(String nombre, Tipo tipo, RolSimbolo rol,
                                Mutabilidad mutabilidad, int linea, int columna) {
            return new Simbolo(nombre, tipo, rol, Visibilidad.PRIVADO, mutabilidad,
                    linea, columna, Collections.emptyList());
        }

        static Simbolo metodo(String nombre, Tipo retorno, Visibilidad visibilidad,
                              List<Tipo> parametros, int linea, int columna) {
            return new Simbolo(nombre, retorno, RolSimbolo.METODO, visibilidad,
                    Mutabilidad.INMUTABLE, linea, columna, parametros);
        }

        boolean tieneMismaFirma(Simbolo otro) {
            return rol == RolSimbolo.METODO && otro.rol == RolSimbolo.METODO
                    && nombre.equals(otro.nombre) && parametros.equals(otro.parametros);
        }
    }

    /**
     * Ámbito mutable. Conserva espacios de nombres separados para tipos,
     * valores y métodos, igual que Java: una clase, un campo y un método no se
     * resuelven todos de la misma forma, y los métodos pueden sobrecargarse.
     */
    static final class AmbitoSimbolos {
        final Ambito tipo;
        final AmbitoSimbolos padre;
        private final List<AmbitoSimbolos> hijos = new ArrayList<>();
        List<AmbitoSimbolos> getHijos() { return Collections.unmodifiableList(hijos); }
        List<Simbolo> getSimbolos() {
            List<Simbolo> resultado = new ArrayList<>(tipos.values());
            resultado.addAll(valores.values()); resultado.addAll(metodosLocales()); resultado.addAll(constructores);
            return Collections.unmodifiableList(resultado);
        }
        private final List<Simbolo> constructores = new ArrayList<>();
        private final Map<String, Simbolo> tipos = new LinkedHashMap<>();
        private final Map<String, Simbolo> valores = new LinkedHashMap<>();
        private final Map<String, List<Simbolo>> metodos = new LinkedHashMap<>();

        AmbitoSimbolos(Ambito tipo, AmbitoSimbolos padre) {
            this.tipo = tipo;
            this.padre = padre;
            if (padre != null) padre.hijos.add(this);
        }

        /** Registra el símbolo si no colisiona en su espacio de nombres local. */
        boolean declarar(Simbolo simbolo) {
            simbolo.ambitoDeclaracion = this;
            if (simbolo.rol == RolSimbolo.CONSTRUCTOR) { constructores.add(simbolo); return true; }
            if (simbolo.rol == RolSimbolo.CLASE) {
                if (tipos.containsKey(simbolo.nombre)) return false;
                tipos.put(simbolo.nombre, simbolo);
                return true;
            }
            if (simbolo.rol == RolSimbolo.METODO) {
                List<Simbolo> sobrecargas = metodos.computeIfAbsent(simbolo.nombre,
                        clave -> new ArrayList<>());
                for (Simbolo existente : sobrecargas) {
                    if (existente.tieneMismaFirma(simbolo)) return false;
                }
                sobrecargas.add(simbolo);
                return true;
            }
            if (valores.containsKey(simbolo.nombre)) return false;
            valores.put(simbolo.nombre, simbolo);
            return true;
        }

        Simbolo resolverValor(String nombre) {
            for (AmbitoSimbolos actual = this; actual != null; actual = actual.padre) {
                Simbolo simbolo = actual.valores.get(nombre);
                if (simbolo != null) return simbolo;
            }
            return null;
        }

        Simbolo resolverTipo(String nombre) {
            for (AmbitoSimbolos actual = this; actual != null; actual = actual.padre) {
                Simbolo simbolo = actual.tipos.get(nombre);
                if (simbolo != null) return simbolo;
            }
            return null;
        }

        List<Simbolo> resolverMetodosLocales(String nombre) {
            List<Simbolo> encontrados = metodos.get(nombre);
            return encontrados == null ? Collections.emptyList()
                    : Collections.unmodifiableList(encontrados);
        }

        List<Simbolo> metodosLocales() {
            List<Simbolo> resultado = new ArrayList<>();
            for (List<Simbolo> sobrecargas : metodos.values()) resultado.addAll(sobrecargas);
            return Collections.unmodifiableList(resultado);
        }

        /** Impide que una local o parámetro oculte otra local aún visible. */
        boolean hayLocalVisible(String nombre) {
            for (AmbitoSimbolos actual = this;
                 actual != null && actual.tipo != Ambito.CLASE && actual.tipo != Ambito.GLOBAL;
                 actual = actual.padre) {
                Simbolo simbolo = actual.valores.get(nombre);
                if (simbolo != null && (simbolo.rol == RolSimbolo.PARAMETRO
                        || simbolo.rol == RolSimbolo.VARIABLE || simbolo.rol == RolSimbolo.ITERADOR)) {
                    return true;
                }
            }
            return false;
        }

        Map<Simbolo, Boolean> estadoInicializacion() {
            Map<Simbolo, Boolean> estado = new IdentityHashMap<>();
            recolectarInicializacion(estado);
            return estado;
        }

        private void recolectarInicializacion(Map<Simbolo, Boolean> estado) {
            if (padre != null) padre.recolectarInicializacion(estado);
            for (Simbolo simbolo : valores.values()) estado.put(simbolo, simbolo.inicializado);
        }

        void restaurarInicializacion(Map<Simbolo, Boolean> estado) {
            for (Map.Entry<Simbolo, Boolean> entrada : estado.entrySet()) {
                entrada.getKey().inicializado = entrada.getValue();
            }
        }

        /** Una variable queda definitivamente inicializada solo si ambas ramas lo garantizan. */
        void fusionarInicializacion(Map<Simbolo, Boolean> antes,
                                    Map<Simbolo, Boolean> thenEstado,
                                    Map<Simbolo, Boolean> elseEstado) {
            for (Simbolo simbolo : antes.keySet()) {
                boolean inicializada = Boolean.TRUE.equals(thenEstado.get(simbolo))
                        && Boolean.TRUE.equals(elseEstado.get(simbolo));
                simbolo.inicializado = inicializada;
            }
        }
    }

    /** Información de clase preparada durante la pasada de declaraciones. */
    static final class ClaseInfo {
        final Simbolo simbolo;
        final AmbitoSimbolos miembros;
        final String nombreBase;
        ClaseInfo base;
        final Simbolo constructor;

        ClaseInfo(Simbolo simbolo, AmbitoSimbolos global, String nombreBase) {
            this.simbolo = simbolo;
            this.miembros = new AmbitoSimbolos(Ambito.CLASE, global);
            this.nombreBase = nombreBase;
            constructor = new Simbolo(simbolo.nombre, simbolo.tipo, RolSimbolo.CONSTRUCTOR,
                    Visibilidad.PUBLICO, Mutabilidad.INMUTABLE, simbolo.linea, simbolo.columna, Collections.emptyList());
            constructor.propietaria = this;
            constructor.ambitoDeclaracion = miembros;
            miembros.declarar(constructor);
        }
    }

    /**
     * Atributos heredados del visitante. Los datos de clase y retorno se
     * reemplazan al entrar a una construcción; cada bloque obtiene un ámbito
     * hijo mutable, por lo que sus sentencias comparten declaraciones previas.
     */
    static final class Contexto {
        final AmbitoSimbolos ambitoActual;
        final ClaseInfo claseActual;
        final Tipo tipoRetornoMetodo;
        Simbolo metodoActual;

        private Contexto(AmbitoSimbolos ambitoActual, ClaseInfo claseActual,
                         Tipo tipoRetornoMetodo) {
            this.ambitoActual = ambitoActual;
            this.claseActual = claseActual;
            this.tipoRetornoMetodo = tipoRetornoMetodo;
        }

        static Contexto global() {
            return new Contexto(new AmbitoSimbolos(Ambito.GLOBAL, null), null, VOID);
        }

        Contexto conClase(ClaseInfo clase) {
            return new Contexto(clase.miembros, clase, VOID);
        }

        Contexto conMetodo(Simbolo metodo) {
            Contexto resultado = new Contexto(new AmbitoSimbolos(Ambito.METODO, ambitoActual), claseActual, metodo.tipo);
            resultado.metodoActual = metodo;
            return resultado;
        }

        Contexto conBloque() {
            Contexto resultado = new Contexto(new AmbitoSimbolos(Ambito.BLOQUE, ambitoActual), claseActual, tipoRetornoMetodo);
            resultado.metodoActual = metodoActual;
            return resultado;
        }
    }

    static boolean esNumerico(Tipo tipo) {
        return tipo.equals(INT) || tipo.equals(FLOAT);
    }

    static boolean esReferencia(Tipo tipo) {
        return tipo.categoria == CategoriaTipo.STRING
                || tipo.categoria == CategoriaTipo.LISTA
                || tipo.categoria == CategoriaTipo.CLASE;
    }

    /**
     * Asignación estilo Java: los enteros se promocionan a float; la conversión
     * inversa requiere un cast, que el lenguaje todavía no tiene. null solo se
     * asigna a referencias. La compatibilidad de clases se resuelve con la jerarquía.
     */
    static boolean esAsignable(Tipo destino, Tipo origen) {
        if (destino.equals(ERROR) || origen.equals(ERROR)) return true;
        if (destino.equals(origen)) return true;
        if (destino.categoria == CategoriaTipo.LISTA && origen.categoria == CategoriaTipo.LISTA) {
            return origen.elemento.equals(VACIO)
                    || (destino.elemento.categoria == CategoriaTipo.LISTA
                        && origen.elemento.categoria == CategoriaTipo.LISTA
                        && esAsignable(destino.elemento, origen.elemento));
        }
        if (destino.equals(FLOAT) && origen.equals(INT)) return true;
        return origen.equals(NULL) && esReferencia(destino);
    }

    /** int op int produce int; toda operación que incluya float produce float. */
    static Tipo resultadoAritmetico(Tipo izquierdo, Tipo derecho) {
        if (izquierdo.equals(ERROR) || derecho.equals(ERROR)) return ERROR;
        if (!esNumerico(izquierdo) || !esNumerico(derecho)) return ERROR;
        return izquierdo.equals(FLOAT) || derecho.equals(FLOAT) ? FLOAT : INT;
    }

    /**
     * '+' permite concatenación cuando al menos un operando es string, como en
     * Java. Los demás operadores aritméticos son exclusivamente numéricos.
     */
    static Tipo resultadoSuma(Tipo izquierdo, Tipo derecho) {
        if (izquierdo.equals(ERROR) || derecho.equals(ERROR)) return ERROR;
        if (izquierdo.equals(STRING) || derecho.equals(STRING)) {
            return izquierdo.equals(VOID) || derecho.equals(VOID) ? ERROR : STRING;
        }
        return resultadoAritmetico(izquierdo, derecho);
    }

    static boolean admiteComparacionOrden(Tipo izquierdo, Tipo derecho) {
        return esNumerico(izquierdo) && esNumerico(derecho);
    }

    /** Igualdad entre números, booleanos o referencias del mismo tipo. */
    static boolean admiteIgualdad(Tipo izquierdo, Tipo derecho) {
        if (izquierdo.equals(ERROR) || derecho.equals(ERROR)) return true;
        if (esNumerico(izquierdo) && esNumerico(derecho)) return true;
        if (izquierdo.equals(BOOL) && derecho.equals(BOOL)) return true;
        if (izquierdo.equals(NULL)) return derecho.equals(NULL) || esReferencia(derecho);
        if (derecho.equals(NULL)) return esReferencia(izquierdo);
        return esReferencia(izquierdo) && izquierdo.equals(derecho);
    }

    static boolean esCondicion(Tipo tipo) {
        return tipo.equals(BOOL) || tipo.equals(ERROR);
    }

    static boolean esColeccionParaExist(Tipo tipo) {
        return tipo.categoria == CategoriaTipo.LISTA || tipo.equals(ERROR);
    }

    static Mutabilidad mutabilidadDeDeclaracion(String palabra) {
        return "let".equals(palabra) ? Mutabilidad.INMUTABLE : Mutabilidad.MUTABLE;
    }

    /*
     * Reglas aplicadas por el recorrido del AST:
     *
     * - var y let requieren inicializador. Ambos infieren su tipo; let no puede
     *   reasignarse. Un literal [] necesita el tipo contextual de una declaración
     *   explícita (por ejemplo list<int> xs = []), pues var xs = [] no es inferible.
     * - Los campos obtienen el valor por defecto de su tipo, como en Java. Las
     *   variables locales sin inicializador deben estar definitivamente asignadas
     *   antes de usarse.
     * - Un ámbito anidado puede ocultar un campo, pero no puede redeclarar un
     *   parámetro ni una variable local cuyo ámbito siga activo.
     * - private solo es accesible desde miembros de la misma clase; public es
     *   accesible desde cualquier clase visible. Las importaciones aportan clases
     *   al ámbito global y no introducen miembros directamente.
     * - if, while y repeat (Expr) exigen bool. repeat each exige list<T> y el
     *   tipo declarado del iterador debe aceptar T. Exist(Expr) exige list<T>,
     *   acorde con el uso canónico Exist(valores).
     * - return; solo es válido en void. return Expr exige que Expr sea asignable
     *   al tipo de retorno. Todo método no void debe retornar en cada camino.
     * - new Clase() exige una clase declarada y usa su constructor implícito sin
     *   parámetros. Los argumentos de construcción se rechazan semánticamente.
     *   Una llamada a método debe resolver una firma con misma aridad y argumentos
     *   asignables a sus parámetros.
     */
}
