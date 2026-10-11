import java.util.*;

/** H4: datos y puentes ABI del runtime. No emite cuerpos de métodos ni un main nativo. */
final class SoporteNativoIr {
    String nasm(ModuloAtomicoIr m) { return emitir(m, false); }
    // Proyección equivalente para verificar también la ABI con GNU as.
    String gas(ModuloAtomicoIr m) { return emitir(m, true); }
    private String emitir(ModuloAtomicoIr m, boolean gas) {
        if (gas && m.layout.destino != DestinoNativoIr.LINUX_X64) throw new IllegalArgumentException("GAS H4 solo admite linux-x64");
        DatosNativosIr d = new DatosNativosIr(m); Emisor e = new Emisor(gas);
        AdaptadorPlataformaNasm plataforma = new AdaptadorPlataformaNasm(m.layout.destino);
        Set<String> externos = new LinkedHashSet<>(); Map<String, LlamadaAtomica> llamadas = new LinkedHashMap<>();
        for (LayoutProgramaIr.Clase c : d.clases.values()) for (FirmaIr f : c.virtuales) externos.add(LayoutProgramaIr.nombreFuncion(f));
        if (m.entrada != null) {
            externos.add(LayoutProgramaIr.nombreFuncion(m.entrada));
            FirmaIr init = d.clases.get(m.entrada.propietaria).inicializador;
            if (init != null) externos.add(LayoutProgramaIr.nombreFuncion(init));
        }
        for (FuncionAtomicaIr f : m.funciones) for (CuadruploIr q : f.codigo) if (q.operacion == CuadruploIr.Operacion.LLAMAR_RUNTIME) {
            LlamadaAtomica l = q.fuente1.llamada; llamadas.putIfAbsent(l.simbolo, l); externos.add(destino(l));
        }
        if (gas) e.linea(".intel_syntax noprefix"); else e.linea("bits 64\ndefault rel");
        for (String n : externos) e.linea((gas ? ".extern " : "extern ") + n);
        e.seccion(m.layout.destino == DestinoNativoIr.WINDOWS_X64 ? ".rdata rdata align=8" : ".rodata");
        for (Map.Entry<String, String> s : d.strings.entrySet()) {
            e.etiqueta(s.getValue(), true); byte[] bytes = DatosNativosIr.utf8(s.getKey()); e.quads(Long.toString(bytes.length), "0"); e.bytes(bytes, true);
        }
        for (Map.Entry<String, OrigenIr> o : d.origenes.entrySet()) {
            e.etiqueta(o.getKey() + "_archivo", false); e.bytes(DatosNativosIr.utf8(o.getValue().archivo), true);
        }
        e.seccion(m.layout.destino == DestinoNativoIr.WINDOWS_X64 ? ".rdata rdata align=8" : ".data.rel.ro");
        for (Map.Entry<TipoIr, String> t : d.tipos.entrySet()) {
            e.etiqueta(t.getValue(), true);
            e.quads(Integer.toString(kind(t.getKey())), t.getKey().elemento == null ? "0" : d.tipos.get(t.getKey().elemento),
                    t.getKey().clase == null ? "0" : DatosNativosIr.clase(t.getKey().clase));
        }
        for (LayoutProgramaIr.Clase c : d.clases.values()) {
            String nombre = DatosNativosIr.clase(c.nombre), tabla = DatosNativosIr.vtable(c.nombre);
            e.etiqueta(tabla, true);
            if (c.virtuales.isEmpty()) e.quads("0");
            else for (FirmaIr f : c.virtuales) e.quads(LayoutProgramaIr.nombreFuncion(f));
            e.etiqueta(nombre + "_campos", false);
            if (c.tiposCampos.isEmpty()) e.quads("0"); else {
                List<String> campos = new ArrayList<>(c.campos.keySet());
                campos.sort(Comparator.comparingInt(c.campos::get));
                for (String campo : campos) e.quads(d.tipos.get(c.tiposCampos.get(campo)));
            }
            e.etiqueta(nombre, true);
            e.quads(d.strings.get(c.nombre), Integer.toString(c.tamano), tabla, c.base == null ? "0" : DatosNativosIr.clase(c.base),
                    Integer.toString(c.campos.size()), nombre + "_campos");
        }
        for (Map.Entry<String, OrigenIr> o : d.origenes.entrySet()) {
            e.etiqueta(o.getKey(), true); e.quads(o.getKey() + "_archivo", Integer.toString(o.getValue().linea), Integer.toString(o.getValue().columna));
        }
        e.etiqueta("rt_entry_class", true); e.quads(m.entrada == null ? "0" : DatosNativosIr.clase(m.entrada.propietaria));
        e.etiqueta("rt_entry_main", true); e.quads(m.entrada == null ? "0" : LayoutProgramaIr.nombreFuncion(m.entrada));
        e.etiqueta("rt_entry_init", true);
        FirmaIr init = m.entrada == null ? null : d.clases.get(m.entrada.propietaria).inicializador;
        e.quads(init == null ? "0" : LayoutProgramaIr.nombreFuncion(init));
        e.seccion(".text");
        for (LlamadaAtomica l : llamadas.values()) {
            if (m.layout.destino == DestinoNativoIr.WINDOWS_X64) { e.linea(plataforma.puenteWindows(l, d)); continue; }
            e.etiqueta(l.simbolo, true); e.linea("    push rbp\n    mov rbp, rsp");
            if (l.consola != null) {
                if (l.consola.imprime()) e.linea("    mov rcx, r10\n    mov edx, " + (l.consola == RuntimeConsolaId.CONSOLA_IMPRIMIR_LINEA ? "1" : "0"));
                else e.linea("    mov rsi, r10");
            } else switch (l.helper) {
                case COMPROBAR_NULO: e.linea("    mov rsi, r10"); break;
                case DIVIDIR_FLOAT: e.linea("    mov rdi, r10"); break;
                case A_STRING:
                    if (l.parametros.get(0).equals(TipoIr.FLOAT)) e.linea("    movq rdi, xmm0");
                    e.direccion("rsi", d.tipos.get(l.parametros.get(0))); e.linea("    mov rdx, r10"); break;
                case NUEVO_OBJETO: e.direccion("rdi", DatosNativosIr.clase(l.retorno.clase)); e.linea("    mov rsi, r10"); break;
                case NUEVA_LISTA: e.direccion("rsi", d.tipos.get(l.retorno.elemento)); e.linea("    mov rdx, r10"); break;
                case COMPROBAR_INDICE: case DIVIDIR_INT: case CONCATENAR: e.linea("    mov rdx, r10"); break;
                case IGUAL_STRING: break;
                default: throw new AssertionError(l.helper);
            }
            e.linea("    call " + destino(l) + (gas ? "@PLT" : " wrt ..plt")); e.linea("    pop rbp\n    ret");
        }
        if (gas) e.linea(".section .note.GNU-stack,\"\",@progbits"); else e.linea(plataforma.pilaNoEjecutable());
        return e.s.toString();
    }
    static String destino(LlamadaAtomica l) {
        if (l.consola != null) switch (l.consola) {
            case CONSOLA_IMPRIMIR: case CONSOLA_IMPRIMIR_LINEA: return "rt_lang_print";
            case CONSOLA_LEER_INT: return "rt_lang_read_int";
            case CONSOLA_LEER_FLOAT: return "rt_lang_read_float";
            case CONSOLA_LEER_BOOL: return "rt_lang_read_bool";
            case CONSOLA_LEER_LINEA: return "rt_lang_read_line";
            default: throw new AssertionError(l.consola);
        }
        switch (l.helper) {
            case COMPROBAR_NULO: return "rt_check_null"; case COMPROBAR_INDICE: return "rt_check_index";
            case DIVIDIR_INT: return "rt_div_int"; case DIVIDIR_FLOAT: return "rt_div_float";
            case A_STRING: return "rt_value_to_string"; case CONCATENAR: return "rt_concat";
            case IGUAL_STRING: return "rt_string_equal"; case NUEVO_OBJETO: return "rt_object_new";
            case NUEVA_LISTA: return "rt_list_new"; default: throw new AssertionError(l.helper);
        }
    }
    private static int kind(TipoIr t) {
        switch (t.categoria) {
            case INT: return 1; case FLOAT: return 2; case BOOL: return 3; case STRING: return 4;
            case NULO: return 5; case OBJETO: return 6; case LISTA: return 7; default: throw new AssertionError(t);
        }
    }
    private static final class Emisor {
        final StringBuilder s = new StringBuilder(); final boolean gas;
        Emisor(boolean gas) { this.gas = gas; }
        void linea(String s) { this.s.append(s).append('\n'); }
        void seccion(String n) { linea((gas ? ".section " : "section ") + n); }
        void etiqueta(String n, boolean global) { linea(gas ? ".balign 8" : "align 8"); if (global) linea((gas ? ".global " : "global ") + n); linea(n + ":"); }
        void quads(String... values) { linea("    " + (gas ? ".quad " : "dq ") + String.join(", ", values)); }
        void bytes(byte[] bytes, boolean cero) {
            // Lotes acotados: un literal puede contener Unicode y NUL, sin interpretar escapes de NASM.
            for (int i = 0; i < bytes.length; i += 32) {
                List<String> n = new ArrayList<>(); for (int j = i; j < Math.min(bytes.length, i + 32); j++) n.add(Integer.toString(bytes[j] & 255));
                linea("    " + (gas ? ".byte " : "db ") + String.join(", ", n));
            }
            if (cero) linea("    " + (gas ? ".byte 0" : "db 0"));
        }
        void direccion(String registro, String simbolo) { linea("    lea " + registro + ", [" + (gas ? "rip + " : "rel ") + simbolo + "]"); }
    }
}
