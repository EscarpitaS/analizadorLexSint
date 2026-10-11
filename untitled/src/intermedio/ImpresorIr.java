import java.util.Locale;
import java.util.stream.Collectors;
import runtime.jdk11.FloatingDecimal;

/** Exportación canónica legible. No contiene timestamps ni IDs de identidad Java. */
final class ImpresorIr {
    String imprimir(ModuloIr modulo) {
        ResultadoVerificacionIr verificacion = new VerificadorIr().verificar(modulo);
        if (!verificacion.esExitoso()) throw new IllegalArgumentException("IR inválida: " + verificacion.getErrores().get(0));
        StringBuilder s = new StringBuilder("modulo ").append(modulo.nombre).append(" contrato=")
                .append(ContratoEjecucion.VERSION).append('\n');
        s.append("entrada ").append(modulo.entrada == null ? "biblioteca" : modulo.entrada.id()).append('\n');
        for (ClaseIr c : modulo.clases) {
            s.append("clase ").append(c.nombre);
            if (c.consolaIntegrada) s.append(" [runtime]");
            if (c.base != null) s.append(" extends ").append(c.base);
            s.append(" {\n");
            if (c.inicializador != null) s.append("  inicializador ").append(c.inicializador.id()).append('\n');
            for (SimboloIr campo : c.campos) s.append("  campo ").append(simbolo(campo)).append(" @ ").append(origen(campo.origen)).append('\n');
            s.append("}\n");
        }
        for (FuncionIr f : modulo.funciones) {
            s.append("funcion ").append(f.firma.visibilidad.name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(f.firma.id()).append(" -> ").append(f.firma.retorno)
                    .append(" receptor=").append(valor(f.receptor)).append(" parametros=[")
                    .append(f.parametros.stream().map(ImpresorIr::valor).collect(Collectors.joining(", "))).append("] {\n");
            for (SimboloIr local : f.locales) s.append("  local ").append(simbolo(local)).append(" @ ").append(origen(local.origen)).append('\n');
            for (BloqueIr b : f.bloques) {
                s.append(b.id).append(":\n");
                for (InstruccionIr i : b.instrucciones) {
                    s.append("  ");
                    if (i.resultado != null) s.append(valor(i.resultado)).append(" = ");
                    s.append(i.operacion.name().toLowerCase(Locale.ROOT));
                    if (i.runtime != null) s.append(' ').append(i.runtime.name());
                    if (i.simbolo != null) s.append(" &").append(i.simbolo.id);
                    if (i.firma != null) s.append(' ').append(i.firma.id()).append(" -> ").append(i.firma.retorno);
                    if (i.conversion != null) s.append(' ').append(i.conversion.name().toLowerCase(Locale.ROOT));
                    if (!i.operandos.isEmpty()) s.append(' ').append(i.operandos.stream().map(ImpresorIr::valor).collect(Collectors.joining(", ")));
                    s.append(" @ ").append(origen(i.origen)).append('\n');
                }
                TerminadorIr t = b.terminador;
                s.append("  ").append(t.clase.name().toLowerCase(Locale.ROOT));
                if (t.valor != null) s.append(' ').append(valor(t.valor));
                if (!t.destinos.isEmpty()) s.append(' ').append(String.join(", ", t.destinos));
                s.append(" @ ").append(origen(t.origen)).append('\n');
            }
            s.append("}\n");
        }
        return s.toString();
    }
    private static String simbolo(SimboloIr s) { return "&" + s.id + ":" + s.tipo + " " + s.propietaria + "::" + s.nombre; }
    private static String origen(OrigenIr o) { return escapar(o.archivo) + ":" + o.linea + ":" + o.columna; }
    private static String valor(ValorIr v) {
        if (v.clase != ValorIr.Clase.CONSTANTE) return "%" + v.id + ":" + v.tipo;
        return (v.tipo.equals(TipoIr.STRING) ? escapar((String) v.literal)
                : v.tipo.equals(TipoIr.NULO) ? "null" : v.tipo.equals(TipoIr.FLOAT)
                    ? FloatingDecimal.toJavaFormatString((Double) v.literal) : v.literal.toString()) + ":" + v.tipo;
    }
    static String escapar(String texto) {
        StringBuilder s = new StringBuilder("\"");
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '\\': s.append("\\\\"); break;
                case '"': s.append("\\\""); break;
                case '\n': s.append("\\n"); break;
                case '\r': s.append("\\r"); break;
                case '\t': s.append("\\t"); break;
                default:
                    if (Character.isISOControl(c) || Character.isSurrogate(c)) s.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else s.append(c);
            }
        }
        return s.append('"').toString();
    }
}
