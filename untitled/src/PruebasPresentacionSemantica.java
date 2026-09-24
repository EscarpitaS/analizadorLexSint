import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;

/** Integración de recuperación, diagnósticos, árboles y modelos Swing sin pantalla. */
public class PruebasPresentacionSemantica {
    public static void main(String[] args) throws Exception {
        ejecutar();
        System.out.println("OK: arboles, acciones y diagnosticos combinados aprobados.");
    }

    static void ejecutar() {
        verificar("int x = 1; x += 2;", false, false);
        verificar("int x = ; int y = 2;", true, false);
        verificar("int x = true;", false, true);
        Main.ResultadoAnalisis mixto = verificar("int x = ; bool y = 1;", true, true);
        exigir(mixto.estadoDiagnosticos().contains("SINTACTICOS + SEMANTICOS"), "Falta la combinación de fases.");
        exigir(mixto.semantico.getErrores().size() == 1, "El fragmento incompleto no debe generar errores semánticos adicionales.");
        verificar("var x = ; int y = x; bool z = 1;", true, true);
        verificar("if true { int x = 1; } bool z = 1;", true, true);
        verificar("return ; int x = 1;", false, true);
        verificar("int x = 1 bool y = 1;", true, true);
        Main.ResultadoAnalisis lex = verificar("int x = @;", false, false);
        exigir(lex.lexico.hasErrors() && lex.semantico == null, "Se conserva la detención ante error léxico.");

        Main.ResultadoAnalisis valido = verificar("int x = 1;", false, false);
        NodoArbolSintactico arbol = valido.sintactico.getArbol();
        String sintactico = arbol.imprimir();
        String conAcciones = arbol.imprimirConAcciones();
        String semantico = arbol.imprimirSemantico();
        exigir(!sintactico.contains("{acción:"), "La impresión sintáctica original debe poder usarse sin anotaciones.");
        exigir(conAcciones.contains("Declarar x: int"), "Falta la acción de declaración.");
        exigir(semantico.contains("[tipo: int]"), "Falta el tipo sintetizado.");
        exigir(Main.generarReporte(java.util.Arrays.asList(valido)).contains("ARBOL SEMANTICO"), "Falta el árbol en el reporte.");
        DefaultMutableTreeNode swing = AnalizadorLexicoUI.crearNodoSwing(arbol, true);
        exigir(swing.getLeafCount() > 1, "Modelo Swing vacío.");
        StringBuilder textos = new StringBuilder();
        java.util.Enumeration<?> recorrido = swing.preorderEnumeration();
        while (recorrido.hasMoreElements()) textos.append(recorrido.nextElement()).append('\n');
        exigir(textos.toString().contains("Acción: Declarar x: int") && textos.toString().contains("[tipo: int]"),
                "El modelo Swing debe mostrar acciones y tipos.");
        new Semantico().analizar(arbol);
        exigir(semantico.equals(arbol.imprimirSemantico()), "Las acciones se duplicaron al repetir el análisis.");
    }

    private static Main.ResultadoAnalisis verificar(String cuerpo, boolean sintactico, boolean semantico) {
        Path archivo = null;
        try {
            archivo = Files.createTempFile("semantico-vistas-", ".txt");
            Files.writeString(archivo, "{ class Main { public main() { " + cuerpo + " } } }");
            List<Main.ResultadoAnalisis> lista = Main.analizarProyecto(archivo, false);
            Main.ResultadoAnalisis resultado = lista.get(0);
            exigir((resultado.sintactico != null && resultado.sintactico.hasErrors()) == sintactico,
                    "Sintaxis inesperada: " + cuerpo);
            exigir((resultado.semantico != null && resultado.semantico.hasErrors()) == semantico,
                    "Semántica inesperada: " + cuerpo + " " + resultado.diagnosticos());
            exigir(resultado.resultadoSemantico != null, "Falta la salida final del proyecto.");
            if (resultado.lexico.hasErrors() || sintactico || semantico) {
                exigir(!resultado.resultadoSemantico.esExitoso() && resultado.resultadoSemantico.getAstValidado() == null,
                        "Se expuso un AST validado con errores de alguna fase.");
            } else exigir(resultado.resultadoSemantico.esExitoso(), "No se habilitó un AST correcto.");
            if (sintactico) {
                exigir(resultado.semantico != null, "Debe ejecutarse la semántica parcial.");
                String reporte = Main.generarReporte(lista);
                exigir(reporte.contains("ARBOL SINTACTICO CON ACCIONES") && reporte.contains("ARBOL SEMANTICO")
                        && reporte.contains("Análisis semántico parcial"), "Reporte parcial incompleto.");
            }
            int linea = -1, columna = -1;
            for (compiladorErrores error : resultado.diagnosticos()) {
                exigir(error.linea > linea || error.linea == linea && error.columna >= columna,
                        "Diagnósticos fuera de orden.");
                linea = error.linea;
                columna = error.columna;
            }
            return resultado;
        } catch (Exception e) { throw new AssertionError(e); }
        finally {
            if (archivo != null) try { Files.delete(archivo); } catch (Exception e) { throw new AssertionError(e); }
        }
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) throw new AssertionError(mensaje);
    }
}
