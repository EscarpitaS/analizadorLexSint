import java.util.*;

/** Layout x86-64 versión 1. Los offsets físicos se fijan antes del backend. */
final class LayoutProgramaIr {
    static final int RANURA = 8, CABECERA_OBJETO = 16, CABECERA_LISTA = 16;
    static final int OFFSET_VTABLE = 0, OFFSET_CLASE = 8, OFFSET_LONGITUD = 0;
    final DestinoNativoIr destino;
    final Map<String, Clase> clases;
    final Map<String, Marco> marcos;
    LayoutProgramaIr(Map<String, Clase> clases, Map<String, Marco> marcos) {
        this(DestinoNativoIr.LINUX_X64, clases, marcos);
    }
    LayoutProgramaIr(DestinoNativoIr destino, Map<String, Clase> clases, Map<String, Marco> marcos) {
        this.destino = Objects.requireNonNull(destino);
        this.clases = Collections.unmodifiableMap(new LinkedHashMap<>(clases));
        this.marcos = Collections.unmodifiableMap(new LinkedHashMap<>(marcos));
    }
    static Map<String, Clase> clases(ModuloIr modulo) {
        Map<String, ClaseIr> fuente = new LinkedHashMap<>();
        for (ClaseIr c : modulo.clases) fuente.put(c.nombre, c);
        Map<String, Clase> calculadas = new LinkedHashMap<>();
        for (ClaseIr c : modulo.clases) calcularClase(c.nombre, fuente, modulo.funciones, calculadas);
        // Orden del módulo, independiente del orden de visita por herencia.
        Map<String, Clase> orden = new LinkedHashMap<>();
        for (ClaseIr c : modulo.clases) orden.put(c.nombre, calculadas.get(c.nombre));
        return orden;
    }
    private static Clase calcularClase(String nombre, Map<String, ClaseIr> fuente, List<FuncionIr> funciones, Map<String, Clase> salida) {
        if (salida.containsKey(nombre)) return salida.get(nombre);
        ClaseIr c = fuente.get(nombre);
        Clase base = c.base == null ? null : calcularClase(c.base, fuente, funciones, salida);
        Map<String, Integer> campos = new LinkedHashMap<>();
        Map<String, TipoIr> tipos = new LinkedHashMap<>();
        List<FirmaIr> virtuales = new ArrayList<>();
        int tamano = CABECERA_OBJETO;
        if (base != null) { campos.putAll(base.campos); tipos.putAll(base.tiposCampos); virtuales.addAll(base.virtuales); tamano = base.tamano; }
        for (SimboloIr campo : c.campos) {
            campos.put(campo.id, tamano); tipos.put(campo.id, campo.tipo); tamano = Math.addExact(tamano, RANURA);
        }
        for (FuncionIr f : funciones) if (f.firma.propietaria.equals(nombre) && f.firma.visibilidad == FirmaIr.Visibilidad.PUBLICA) {
            int slot = -1;
            for (int i = 0; i < virtuales.size(); i++) if (clave(virtuales.get(i)).equals(clave(f.firma))) { slot = i; break; }
            if (slot < 0) virtuales.add(f.firma); else virtuales.set(slot, f.firma);
        }
        Clase layout = new Clase(nombre, c.base, c.consolaIntegrada, tamano, campos, tipos, virtuales, c.inicializador);
        salida.put(nombre, layout); return layout;
    }
    static String clave(FirmaIr f) { return f.nombre + "(" + f.parametros + ")"; }
    static int alinear16(int bytes) { return Math.multiplyExact(Math.floorDiv(Math.addExact(bytes, 15), 16), 16); }
    static String tipo(TipoIr t) {
        switch (t.categoria) {
            case INT: return "i"; case FLOAT: return "f"; case BOOL: return "b"; case STRING: return "s";
            case VOID: return "v"; case NULO: return "n";
            case OBJETO: return "o" + t.clase.length() + "_" + t.clase;
            case LISTA: return "l_" + tipo(t.elemento);
            case DIRECCION: return "d_" + tipo(t.elemento);
            default: throw new AssertionError(t);
        }
    }
    static String nombreFuncion(FirmaIr f) {
        StringBuilder n = new StringBuilder("fn_C").append(f.propietaria.length()).append('_').append(f.propietaria)
                .append("_M").append(f.nombre.length()).append('_').append(f.nombre).append("_P").append(f.parametros.size());
        for (TipoIr t : f.parametros) n.append('_').append(tipo(t));
        return n.toString();
    }
    static final class Clase {
        final String nombre, base;
        final boolean consolaIntegrada;
        final int tamano;
        final Map<String, Integer> campos;
        final Map<String, TipoIr> tiposCampos;
        final List<FirmaIr> virtuales;
        final FirmaIr inicializador;
        Clase(String nombre, String base, boolean consola, int tamano, Map<String, Integer> campos, Map<String, TipoIr> tipos, List<FirmaIr> virtuales) {
            this(nombre, base, consola, tamano, campos, tipos, virtuales, null);
        }
        Clase(String nombre, String base, boolean consola, int tamano, Map<String, Integer> campos, Map<String, TipoIr> tipos, List<FirmaIr> virtuales, FirmaIr inicializador) {
            this.inicializador = inicializador;
            this.nombre = nombre; this.base = base; consolaIntegrada = consola; this.tamano = tamano;
            this.campos = Collections.unmodifiableMap(new LinkedHashMap<>(campos));
            tiposCampos = Collections.unmodifiableMap(new LinkedHashMap<>(tipos));
            this.virtuales = Collections.unmodifiableList(new ArrayList<>(virtuales));
        }
        int offsetVirtual(FirmaIr firma) {
            for (int i = 0; i < virtuales.size(); i++) if (clave(virtuales.get(i)).equals(clave(firma))) return Math.multiplyExact(i, RANURA);
            throw new IllegalArgumentException("Firma sin slot virtual: " + firma.id());
        }
    }
    static final class Marco {
        final Map<String, Integer> valores, locales;
        final Map<String, TipoIr> tiposValores, tiposLocales;
        final int bytesValores, maxArgumentos, bytesSalientes, tamano;
        Marco(Map<String, Integer> valores, Map<String, Integer> locales, Map<String, TipoIr> tiposValores,
                Map<String, TipoIr> tiposLocales, int bytesValores, int maxArgumentos) {
            this(DestinoNativoIr.LINUX_X64, valores, locales, tiposValores, tiposLocales, bytesValores, maxArgumentos);
        }
        Marco(DestinoNativoIr destino, Map<String, Integer> valores, Map<String, Integer> locales, Map<String, TipoIr> tiposValores,
                Map<String, TipoIr> tiposLocales, int bytesValores, int maxArgumentos) {
            this.valores = Collections.unmodifiableMap(new LinkedHashMap<>(valores));
            this.locales = Collections.unmodifiableMap(new LinkedHashMap<>(locales));
            this.tiposValores = Collections.unmodifiableMap(new LinkedHashMap<>(tiposValores));
            this.tiposLocales = Collections.unmodifiableMap(new LinkedHashMap<>(tiposLocales));
            this.bytesValores = bytesValores; this.maxArgumentos = maxArgumentos;
            bytesSalientes = destino.bytesSalientes(maxArgumentos);
            // CALL deja RSP%16=8; push rbp lo lleva a 0. SUB conserva 0 con este múltiplo.
            tamano = alinear16(Math.addExact(bytesValores, bytesSalientes));
        }
        int offset(ValorIr v) {
            if (v.clase == ValorIr.Clase.CONSTANTE || !valores.containsKey(v.id)) throw new IllegalArgumentException("Valor sin ranura: " + v.id);
            return valores.get(v.id);
        }
    }
    static final class ConstructorMarco {
        final Map<String, Integer> valores = new LinkedHashMap<>(), locales = new LinkedHashMap<>();
        final Map<String, TipoIr> tiposValores = new LinkedHashMap<>(), tiposLocales = new LinkedHashMap<>();
        int bytes, maxArgumentos;
        void valor(ValorIr v) {
            if (v.clase == ValorIr.Clase.CONSTANTE || valores.containsKey(v.id)) return;
            bytes = Math.addExact(bytes, RANURA); valores.put(v.id, -bytes); tiposValores.put(v.id, v.tipo);
        }
        void local(SimboloIr s) {
            bytes = Math.addExact(bytes, RANURA); locales.put(s.id, -bytes); tiposLocales.put(s.id, s.tipo);
        }
        Marco construir() { return construir(DestinoNativoIr.LINUX_X64); }
        Marco construir(DestinoNativoIr destino) { return new Marco(destino, valores, locales, tiposValores, tiposLocales, bytes, maxArgumentos); }
    }
}
