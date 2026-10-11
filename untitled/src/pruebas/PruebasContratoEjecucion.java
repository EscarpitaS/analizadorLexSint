public final class PruebasContratoEjecucion {
    private static int casos;
    public static void main(String[] args) { ejecutar(); }
    static void ejecutar() {
        casos = 0;
        comprobar(ContratoEjecucion.DESTINO.equals("nasm-linux-x86_64-elf64"), "Destino nativo");
        comprobar(ContratoEjecucion.literalEntero("9223372036854775807") == Long.MAX_VALUE, "Máximo int64");
        comprobar(ContratoEjecucion.literalEntero("-9223372036854775808") == Long.MIN_VALUE, "Mínimo int64");
        comprobar(ContratoEjecucion.literalEntero("00012") == 12, "Base decimal");
        for (String texto : new String[]{"9223372036854775808", "-9223372036854775809", "1.0", "1e3", "+1", "", " 1", "0x10"})
            falla(() -> ContratoEjecucion.literalEntero(texto), IllegalArgumentException.class);
        comprobar(ContratoEjecucion.sumarEnteros(Long.MAX_VALUE, 1) == Long.MIN_VALUE, "Overflow suma");
        comprobar(ContratoEjecucion.restarEnteros(Long.MIN_VALUE, 1) == Long.MAX_VALUE, "Overflow resta");
        comprobar(ContratoEjecucion.multiplicarEnteros(Long.MAX_VALUE, 2) == -2, "Overflow producto");
        comprobar(ContratoEjecucion.negarEntero(Long.MIN_VALUE) == Long.MIN_VALUE, "Overflow menos unario");
        comprobar(ContratoEjecucion.dividirEnteros(-7, 2) == -3 && ContratoEjecucion.dividirEnteros(7, -2) == -3, "División trunca hacia cero");
        comprobar(ContratoEjecucion.dividirEnteros(Long.MIN_VALUE, -1) == Long.MIN_VALUE, "División extrema sin trampa");
        falla(() -> ContratoEjecucion.dividirEnteros(1, 0), ArithmeticException.class);
        comprobar(ContratoEjecucion.literalFlotante("0.1") == 0.1, "Redondeo binary64");
        comprobar(Double.doubleToRawLongBits(ContratoEjecucion.literalFlotante("-0.0")) == Long.MIN_VALUE, "Cero negativo");
        comprobar(ContratoEjecucion.literalFlotante("0." + "0".repeat(400) + "1") == 0.0, "Underflow de literal");
        for (String texto : new String[]{"9".repeat(400) + ".0", "NaN", "Infinity", "1e3", "1.", ".1", "1.0f", " 1.0"})
            falla(() -> ContratoEjecucion.literalFlotante(texto), IllegalArgumentException.class);
        comprobar(ContratoEjecucion.dividirFlotantes(7.0, 2.0) == 3.5, "División float");
        comprobar(Double.isInfinite(ContratoEjecucion.dividirFlotantes(Double.MAX_VALUE, 0.5)), "Overflow float IEEE");
        comprobar(Double.isNaN(ContratoEjecucion.dividirFlotantes(Double.NaN, 1.0)), "NaN se propaga");
        falla(() -> ContratoEjecucion.dividirFlotantes(1.0, 0.0), ArithmeticException.class);
        falla(() -> ContratoEjecucion.dividirFlotantes(0.0, -0.0), ArithmeticException.class);
        System.out.println("OK: " + casos + " casos del contrato de ejecución.");
    }
    private static void comprobar(boolean condicion, String nombre) {
        casos++; if (!condicion) throw new AssertionError(nombre);
    }
    private static void falla(Runnable accion, Class<? extends Exception> tipo) {
        try { accion.run(); } catch (Exception e) { comprobar(tipo.isInstance(e), "Excepción incorrecta: " + e); return; }
        comprobar(false, "Falta excepción " + tipo.getSimpleName());
    }
}
