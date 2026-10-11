import java.util.*;

/** Texto de depuración de cuádruplos, con bloques separados y ubicaciones fuente abreviadas. */
final class ImpresorAtomicoIr {
    String imprimir(ModuloAtomicoIr m) {
        List<String> errores = new VerificadorAtomicoIr().verificar(m);
        if (!errores.isEmpty()) throw new IllegalArgumentException("IR atómica inválida: " + errores);
        StringBuilder s = new StringBuilder("IR_ATOMICA_V1 ").append(m.nombre).append(m.layout.destino == DestinoNativoIr.LINUX_X64 ? " destino=nasm-linux-x86_64-elf64\n" : " destino=nasm-windows-x86_64-coff\n");
        s.append("entrada ").append(m.entrada == null ? "biblioteca" : LayoutProgramaIr.nombreFuncion(m.entrada)).append('\n');
        for (LayoutProgramaIr.Clase c : m.layout.clases.values()) {
            s.append("clase ").append(c.nombre).append(" bytes=").append(c.tamano).append(" cabecera=16").append(c.consolaIntegrada ? " runtime=Consola" : "")
                    .append(c.base == null ? "" : " base=" + c.base).append('\n');
            if (c.inicializador != null) s.append("  inicializador ").append(LayoutProgramaIr.nombreFuncion(c.inicializador)).append('\n');
            for (String id : c.campos.keySet()) s.append("  campo ").append(id).append(':').append(c.tiposCampos.get(id)).append(" offset=+").append(c.campos.get(id)).append('\n');
            for (int i = 0; i < c.virtuales.size(); i++) s.append("  virtual offset=+").append(i * 8).append(" -> ").append(LayoutProgramaIr.nombreFuncion(c.virtuales.get(i))).append('\n');
        }
        for (FuncionAtomicaIr f : m.funciones) {
            LayoutProgramaIr.Marco marco = m.layout.marcos.get(f.firma.id());
            s.append("funcion ").append(LayoutProgramaIr.nombreFuncion(f.firma)).append(" marco=").append(marco.tamano)
                    .append(" salientes=").append(marco.bytesSalientes).append(" max_args=").append(marco.maxArgumentos).append(" retorno=").append(ConvencionLlamadasIr.retorno(f.firma.retorno)).append('\n');
            for (String id : marco.locales.keySet()) s.append("  local ").append(id).append(':').append(marco.tiposLocales.get(id)).append(" [rbp").append(marco.locales.get(id)).append("]\n");
            for (String id : marco.valores.keySet()) s.append("  valor %").append(id).append(':').append(marco.tiposValores.get(id)).append(" [rbp").append(marco.valores.get(id)).append("]\n");
            s.append("  ; (op, fuente1, fuente2, destino)\n");
            for (CuadruploIr q : f.codigo) {
                boolean etiqueta = q.operacion == CuadruploIr.Operacion.LABEL;
                s.append(etiqueta ? "\n(" : "\t(").append(q.operacion).append(", ").append(operando(q.fuente1, marco)).append(", ")
                        .append(operando(q.fuente2, marco)).append(", ").append(operando(q.destino, marco)).append(") @ ")
                        .append(':').append(q.origen.linea).append(':').append(q.origen.columna).append('\n');
            }
        }
        return s.toString();
    }
    String operando(OperandoAtomico o, LayoutProgramaIr.Marco marco) {
        if (o == null) return "_";
        if (o.clase == OperandoAtomico.Clase.ARGUMENTO) return o.argumento.toString();
        if (o.clase == OperandoAtomico.Clase.ETIQUETA) return o.etiqueta;
        if (o.clase == OperandoAtomico.Clase.LLAMADA) {
            LlamadaAtomica l = o.llamada;
            return l.puntero == null ? l.consola == null ? l.simbolo : l.consola.name()
                    : "*" + l.registroPuntero + "<-" + operando(OperandoAtomico.valor(l.puntero), marco);
        }
        ValorIr v = o.valor;
        if (v.clase != ValorIr.Clase.CONSTANTE) return "%" + v.id + ":" + v.tipo + "[rbp" + marco.offset(v) + "]";
        if (v.tipo.equals(TipoIr.STRING)) return ImpresorIr.escapar((String) v.literal) + ":string";
        if (v.tipo.equals(TipoIr.FLOAT)) return "bits64_0x" + String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits((Double) v.literal)) + ":float";
        return (v.tipo.equals(TipoIr.NULO) ? "null" : String.valueOf(v.literal)) + ":" + v.tipo;
    }
}
