import java.util.*;

/** Perfil físico seleccionado antes de linealizar; no modifica operaciones de la IR. */
enum DestinoNativoIr {
    LINUX_X64("linux-x64", "elf64", ".o"), WINDOWS_X64("windows-x64", "win64", ".obj");
    final String id, formatoObjeto, extensionObjeto;
    DestinoNativoIr(String id, String formato, String extension) { this.id=id; formatoObjeto=formato; extensionObjeto=extension; }
    static DestinoNativoIr seleccionar(String id) {
        for (DestinoNativoIr d : values()) if (d.id.equals(id)) return d;
        throw new IllegalArgumentException("Destino desconocido: " + id);
    }
    List<LugarArgumentoIr> argumentos(List<TipoIr> tipos, boolean entrantes) {
        if (this == LINUX_X64) return ConvencionLlamadasIr.argumentos(tipos, entrantes);
        String[] enteros={"rcx", "rdx", "r8", "r9"}; List<LugarArgumentoIr> lugares=new ArrayList<>();
        for (int i=0;i<tipos.size();i++) {
            TipoIr t=tipos.get(i); String reg=i<4 ? t.equals(TipoIr.FLOAT) ? "xmm"+i : enteros[i] : null;
            lugares.add(new LugarArgumentoIr(i,t,reg,reg==null ? (entrantes?48:32)+8*(i-4) : 0,entrantes));
        }
        return Collections.unmodifiableList(lugares);
    }
    int bytesSalientes(int maxArgumentos) {
        // Reserva conservadora: Windows mantiene shadow space incluso en funciones hoja.
        if (this == LINUX_X64) return LayoutProgramaIr.alinear16(Math.multiplyExact(maxArgumentos,8));
        return LayoutProgramaIr.alinear16(Math.addExact(32,Math.multiplyExact(Math.max(0,maxArgumentos-4),8)));
    }
}
