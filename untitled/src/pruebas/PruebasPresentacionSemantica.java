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
        PruebasVisorAst.ejecutar();
        verificarImportsYDependencias();
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

    private static void verificarImportsYDependencias() {
        Path carpeta = null;
        try {
            carpeta = Files.createTempDirectory("imports-presentacion-");
            Path principal = carpeta.resolve("Main.txt");
            for (String prefijo : new String[] { "com", "org", "net", "edu", "java", "javax" }) {
                Path subcarpeta = Files.createDirectories(carpeta.resolve(prefijo));
                Path persona = subcarpeta.resolve("Persona.txt");
                Files.writeString(persona, "{ class Persona { public main() { } } }");
                Files.writeString(principal, "import " + prefijo + ".Persona; { class Main { public main() { Persona p = new Persona(); } } }");
                List<Main.ResultadoAnalisis> resultados = Main.analizarProyecto(principal, true);
                exigir(resultados.size() == 2, "Se ignoró el import local con prefijo " + prefijo);
                for (Main.ResultadoAnalisis resultado : resultados)
                    exigir(resultado.resultadoSemantico.esExitoso(), "Falló la resolución conjunta de " + prefijo);
            }
            List<Main.ResultadoAnalisis> sinImports = Main.analizarProyecto(principal, false);
            exigir(sinImports.size() == 1 && sinImports.get(0).semantico.hasErrors(),
                    "Se cargaron imports locales con la opción desactivada.");

            Files.writeString(principal, "import java.util.List; import com.NoExiste; { class Main { public main() { } } }");
            List<Main.ResultadoAnalisis> externos = Main.analizarProyecto(principal, true);
            exigir(externos.size() == 1 && externos.get(0).resultadoSemantico.esExitoso(),
                    "Los imports externos sin archivo local deben seguir siendo opcionales.");

            Files.writeString(principal, "import Falta; { class Main { public main() { bool x = 1; } } }");
            List<Main.ResultadoAnalisis> resultados = Main.analizarProyecto(principal, true);
            Main.ResultadoAnalisis raiz = resultados.get(0);
            exigir(raiz.abortadoPorDependencia && raiz.semantico.hasErrors(), "No se conservaron los errores propios.");
            exigir(AnalizadorLexicoUI.crearErrores(raiz).size() == 2,
                    "La tabla y el resumen deben incluir el import faltante y el error semántico.");
            exigir(Main.generarReporte(resultados).contains("TOTAL DE ERRORES: 2"), "Conteo distinto en el reporte.");
            exigir(AnalizadorLexicoUI.crearNodoSwing(raiz.sintactico.getArbol(), true).getLeafCount() > 1,
                    "Se perdió el árbol disponible pese al fallo de import.");
            exigir(raiz.resultadoSemantico.getAstValidado() == null, "Una dependencia inválida habilitó el AST validado.");

            Files.writeString(carpeta.resolve("com/Persona.txt"),
                    "{ class Persona { int f() { return true; } public main() { } } }");
            Files.writeString(principal, "import com.Persona; { class Main { public main() { bool x = 1; } } }");
            resultados = Main.analizarProyecto(principal, true);
            exigir(resultados.size() == 2, "No se cargó la dependencia local inválida.");
            int erroresVisibles = 0;
            for (Main.ResultadoAnalisis resultado : resultados) {
                erroresVisibles += AnalizadorLexicoUI.crearErrores(resultado).size();
                exigir(!resultado.resultadoSemantico.esExitoso(), "Se entregó un proyecto inválido como exitoso.");
                if (resultado.archivo.equals(principal))
                    exigir(resultado.abortadoPorDependencia && resultado.diagnosticos().size() == 1,
                            "No se propagó el fallo preservando el diagnóstico propio.");
            }
            exigir(erroresVisibles == 3 && Main.generarReporte(resultados).contains("TOTAL DE ERRORES: 3"),
                    "Los errores de la dependencia y del principal deben coincidir entre interfaz y reporte.");
            final List<Main.ResultadoAnalisis> paraMostrar = resultados;
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JTabbedPane pestanas = new javax.swing.JTabbedPane();
                pestanas.addTab("Resultado anterior", new javax.swing.JPanel());
                AnalizadorLexicoUI.ResumenResultados resumen = AnalizadorLexicoUI.llenarPestanas(
                        pestanas, paraMostrar, resultado -> new javax.swing.JScrollPane(new javax.swing.JTree(
                                AnalizadorLexicoUI.crearNodoSwing(resultado.sintactico.getArbol(), true))));
                exigir(pestanas.getTabCount() == 2 && pestanas.getTitleAt(1).contains("⚠"),
                        "Debe reemplazar las pestañas anteriores y mostrar el principal con advertencia.");
                int tokens = paraMostrar.stream().mapToInt(r -> r.lexico.getTokens().size() - 1).sum();
                exigir(resumen.errores == 3 && resumen.tokens == tokens,
                        "El resumen ocultó diagnósticos o tokens de un archivo con dependencias inválidas.");
                exigir(pestanas.getComponentAt(1) instanceof javax.swing.JScrollPane,
                        "Se sustituyó la vista del principal por un panel de rechazo.");
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (carpeta != null) {
                try (java.util.stream.Stream<Path> archivos = Files.walk(carpeta)) {
                    for (Path archivo : (Iterable<Path>) archivos.sorted(java.util.Comparator.reverseOrder())::iterator)
                        Files.delete(archivo);
                } catch (Exception e) { throw new AssertionError(e); }
            }
        }
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
