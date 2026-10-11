/** Oráculos escalares compartidos con el futuro intérprete; no ejecuta módulos IR. */
final strictfp class ContratoEjecucion {
    static final String VERSION = "1";
    static final String DESTINO = "nasm-linux-x86_64-elf64";
    private ContratoEjecucion() { }
    static long literalEntero(String texto) {
        if (texto == null || !texto.matches("-?[0-9]+")) throw new IllegalArgumentException("Literal int inválido");
        try { return Long.parseLong(texto); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Literal fuera del rango int64: " + texto, e); }
    }
    static double literalFlotante(String texto) {
        if (texto == null || !texto.matches("-?[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Literal float inválido");
        double valor = Double.parseDouble(texto);
        if (!Double.isFinite(valor)) throw new IllegalArgumentException("Literal fuera del rango float64: " + texto);
        return valor;
    }
    static long dividirEnteros(long dividendo, long divisor) {
        if (divisor == 0) throw new ArithmeticException("DIVISION_POR_CERO");
        // El backend debe aplicar estas dos guardas antes de cqo/idiv.
        if (dividendo == Long.MIN_VALUE && divisor == -1) return Long.MIN_VALUE;
        return dividendo / divisor;
    }
    static double dividirFlotantes(double dividendo, double divisor) {
        if (divisor == 0.0) throw new ArithmeticException("DIVISION_POR_CERO");
        return dividendo / divisor;
    }
    static long sumarEnteros(long a, long b) { return a + b; }
    static long restarEnteros(long a, long b) { return a - b; }
    static long multiplicarEnteros(long a, long b) { return a * b; }
    static long negarEntero(long a) { return -a; }
}
