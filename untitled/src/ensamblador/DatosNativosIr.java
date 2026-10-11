import java.nio.charset.StandardCharsets;
import java.util.*;

/** H4: constantes UTF-8, descriptores y orígenes fijados antes de emitir instrucciones. */
final class DatosNativosIr {
    static final int CABECERA_STRING = 16, TAMANO_TIPO = 24, TAMANO_CLASE = 48, TAMANO_ORIGEN = 24;
    final Map<String, String> strings;
    final Map<TipoIr, String> tipos;
    final Map<String, OrigenIr> origenes;
    final Map<String, LayoutProgramaIr.Clase> clases;
    DatosNativosIr(ModuloAtomicoIr modulo) {
        List<String> errores = new VerificadorAtomicoIr().verificar(modulo);
        if (!errores.isEmpty()) throw new IllegalArgumentException("IR atómica inválida: " + errores);
        Map<String, String> textos = new LinkedHashMap<>(); Map<TipoIr, String> ts = new LinkedHashMap<>();
        Map<String, OrigenIr> os = new LinkedHashMap<>();
        clases = modulo.layout.clases;
        registrarOrigen(os, OrigenIr.GENERADO);
        for (LayoutProgramaIr.Clase c : clases.values()) {
            string(textos, c.nombre); tipo(ts, TipoIr.objeto(c.nombre));
            for (TipoIr t : c.tiposCampos.values()) tipo(ts, t);
        }
        for (FuncionAtomicaIr f : modulo.funciones) for (CuadruploIr q : f.codigo) {
            registrarOrigen(os, q.origen);
            for (OperandoAtomico op : Arrays.asList(q.fuente1, q.fuente2, q.destino)) {
                if (op == null) continue;
                if (op.clase == OperandoAtomico.Clase.VALOR) {
                    tipo(ts, op.valor.tipo);
                    if (op.valor.clase == ValorIr.Clase.CONSTANTE && op.valor.tipo.equals(TipoIr.STRING)) string(textos, (String) op.valor.literal);
                }
                if (op.clase == OperandoAtomico.Clase.LLAMADA) {
                    tipo(ts, op.llamada.retorno); for (TipoIr t : op.llamada.parametros) tipo(ts, t);
                }
            }
        }
        strings = Collections.unmodifiableMap(textos); tipos = Collections.unmodifiableMap(ts); origenes = Collections.unmodifiableMap(os);
    }
    static String clase(String nombre) { return "rt_clase_C" + nombre.length() + "_" + nombre; }
    static String vtable(String nombre) { return "rt_vtable_C" + nombre.length() + "_" + nombre; }
    private static void string(Map<String, String> strings, String s) { strings.computeIfAbsent(s, k -> "rt_string_" + strings.size()); }
    private static void tipo(Map<TipoIr, String> tipos, TipoIr t) {
        if (t.categoria == TipoIr.Categoria.VOID) return;
        if (t.categoria == TipoIr.Categoria.DIRECCION) { tipo(tipos, t.elemento); return; }
        if (t.categoria == TipoIr.Categoria.LISTA) tipo(tipos, t.elemento);
        tipos.putIfAbsent(t, "rt_tipo_" + LayoutProgramaIr.tipo(t));
    }
    private static void registrarOrigen(Map<String, OrigenIr> origenes, OrigenIr o) {
        for (OrigenIr previo : origenes.values()) if (igualOrigen(previo, o)) return;
        if (o.archivo.indexOf('\0') >= 0) throw new IllegalArgumentException("Origen nativo con NUL en archivo");
        origenes.put("rt_origen_" + origenes.size(), o);
    }
    static boolean igualOrigen(OrigenIr a, OrigenIr b) { return a.archivo.equals(b.archivo) && a.linea == b.linea && a.columna == b.columna; }
    String origen(OrigenIr o) {
        for (Map.Entry<String, OrigenIr> e : origenes.entrySet()) if (igualOrigen(o, e.getValue())) return e.getKey();
        throw new IllegalArgumentException("Origen no registrado");
    }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
