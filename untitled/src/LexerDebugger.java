import java.util.*;

public class LexerDebugger {

    /**
     * Método principal para ejecutar pruebas rápidas del léxico.
     */
    public static void main(String[] args) {
        // Ejemplo de código para debugear
        String fuentePrueba =
                "// Comentario inicial (debe ser ignorado)\n" +
                        "{\n" +  // El "Banderazo"
                        "  int x = 42;\n" +
                        "  if (x == 42) {\n" +
                        "    x = x @ 5; // Error léxico intencional\n" +
                        "  }\n" +
                        "}";

        System.out.println(">>> INICIANDO DEPURACIÓN DEL LÉXICO <<<\n");

        // 1. Instanciar y ejecutar el Lexico con los nuevos nombres de métodos
        Lexico lexico = new Lexico(fuentePrueba);
        lexico.tokenizar();

        // 2. Obtener las listas
        List<Token> listaTokens = lexico.getTokens();
        List<compiladorErrores> listaErrores = lexico.getErrors();

        // 3. Inspeccionar el mapa de tokens generado
        inspeccionar(listaTokens, listaErrores);

        // 4. Validar la regla del profesor (Banderazo)
        validarBanderazo(listaTokens);
    }

    /**
     * Imprime una tabla detallada de los tokens en memoria.
     */
    public static void inspeccionar(List<Token> tokens, List<compiladorErrores> errores) {
        System.out.println("============================================================");
        System.out.printf("%-5s | %-18s | %-15s | %-10s\n", "ID", "TIPO TOKEN", "LEXEMA", "UBICACIÓN");
        System.out.println("============================================================");

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);

            // Usamos t.tipo y t.lexema según tu nueva estructura
            String nombreTipo = t.tipo.toString();
            String contenido = t.tipo == tipoToken.EOF ? "EOF" : "'" + t.lexema + "'";
            String pos = "L:" + t.linea + " C:" + t.columna;

            System.out.printf("%-5d | %-18s | %-15s | %-10s\n", i, nombreTipo, contenido, pos);
        }

        if (!errores.isEmpty()) {
            System.out.println("\n------------------------------------------------------------");
            System.out.println("ERRORES DETECTADOS DURANTE EL ESCANEO:");
            for (compiladorErrores e : errores) {
                // Usamos e.TIPOERROR, e.mensaje y e.lexema
                System.out.printf("  [!] %s: %s en '%s' (Linea %d)\n",
                        e.TIPOERROR, e.mensaje, e.lexema, e.linea);
            }
        }
        System.out.println("============================================================\n");
    }

    /**
     * Verifica que el primer token significativo sea la llave de apertura.
     */
    private static void validarBanderazo(List<Token> tokens) {
        System.out.println(">>> VERIFICANDO REGLA DE ESTRUCTURA INICIAL...");

        if (tokens.isEmpty() || (tokens.size() == 1 && tokens.get(0).tipo == tipoToken.EOF)) {
            System.out.println("RESULTADO: El archivo está vacío.");
            return;
        }

        // El primer token significativo es el índice 0
        Token inicial = tokens.get(0);

        if (inicial.tipo == tipoToken.DELIMITADOR && inicial.lexema.equals("{")) {
            System.out.println("RESULTADO: [VÁLIDO] Banderazo '{' detectado correctamente.");
        } else {
            System.err.println("RESULTADO: [ERROR] El programa empieza ilegalmente con '" + inicial.lexema + "'.");
            System.err.println("            Se esperaba el delimitador '{' como primer token.");
        }
    }
}