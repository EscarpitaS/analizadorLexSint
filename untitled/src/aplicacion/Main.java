import java.io.*;
import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Clase Principal del Compilador.
 * Gestiona el flujo de análisis léxico multi-archivo, resolución de imports
 * y validación de estructura global.
 */
public class Main {

    public static void main(String[] args) {
        // Sin argumentos, el uso esperado es la interfaz. IntelliJ puede
        // heredar java.awt.headless=true aunque la sesión tenga DISPLAY.
        if (args.length == 0) {
            System.setProperty("java.awt.headless", "false");
        }

        // 1. Detección de entorno (Gráfico vs Consola). Algunos entornos
        // anuncian DISPLAY pero no permiten conectar con X11; en ese caso
        // AWT lanza AWTError antes de que Swing pueda crear la ventana.
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater(() -> {
                    Path archivoInicial = args.length > 0 ? Paths.get(args[0]) : null;
                    new AnalizadorLexicoUI().mostrar(archivoInicial);
                });
                return;
            }
        } catch (java.awt.AWTError | java.awt.HeadlessException e) {
            if (args.length > 0) {
                System.err.println("No se pudo iniciar la interfaz; se continuará en modo consola.");
            }
        }

        // 2. Modo Consola
        if (args.length == 0) {
            System.err.println("No se pudo abrir la interfaz: la JVM no tiene un entorno gráfico disponible.");
            System.err.println("En IntelliJ elimina -Djava.awt.headless=true de la configuración de ejecución.");
            return;
        }

        Path archivoInicial = obtenerArchivoInicial(args);
        if (archivoInicial == null) return;

        try {
            List<ResultadoAnalisis> resultados = analizarProyecto(archivoInicial, true);
            imprimirResultados(resultados);
        } catch (IOException e) {
            System.err.println("Error de lectura: " + e.getMessage());
        }
    }

    private static Path obtenerArchivoInicial(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);

        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Selecciona el archivo raíz del proyecto");
        selector.setFileFilter(new FileNameExtensionFilter("Archivos de código", "txt", "java", "cpp"));

        if (selector.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            return selector.getSelectedFile().toPath();
        }
        return null;
    }

    /** Inicia el análisis y valida imports locales por defecto. */
    static List<ResultadoAnalisis> analizarProyecto(Path archivoInicial) throws IOException {
        return analizarProyecto(archivoInicial, true);
    }

    static List<ResultadoAnalisis> analizarProyecto(Path archivoInicial,
                                                    boolean validarImportsLocales) throws IOException {
        return analizarProyecto(archivoInicial, null, validarImportsLocales);
    }

    /** Analiza una instantánea del editor sin escribirla en el archivo principal. */
    static List<ResultadoAnalisis> analizarProyecto(Path archivoInicial, String fuenteEditor,
                                                    boolean validarImportsLocales) throws IOException {
        List<ResultadoAnalisis> resultados = new ArrayList<>();
        Set<Path> visitados = new LinkedHashSet<>();
        analizarArchivoConImports(archivoInicial, resultados, visitados, validarImportsLocales, fuenteEditor);
        Map<String, NodoArbolSintactico> unidades = new LinkedHashMap<>();
        for (ResultadoAnalisis resultado : resultados) {
            if (resultado.sintactico != null && resultado.sintactico.getArbol() != null) {
                unidades.put(resultado.archivo.toString(), resultado.sintactico.getArbol());
            }
        }
        Map<String, Semantico> analisis = Semantico.analizarUnidades(unidades);
        for (ResultadoAnalisis resultado : resultados) resultado.semantico = analisis.get(resultado.archivo.toString());
        // Propaga el estado de dependencias después de analizar todas las firmas y cuerpos.
        Map<Path, ResultadoAnalisis> porArchivo = new LinkedHashMap<>();
        for (ResultadoAnalisis resultado : resultados) porArchivo.put(resultado.archivo, resultado);
        boolean cambio;
        do {
            cambio = false;
            for (ResultadoAnalisis resultado : resultados) {
                for (Path dependencia : resultado.dependencias) {
                    ResultadoAnalisis importado = porArchivo.get(dependencia);
                    if (importado != null && (importado.lexico.hasErrors()
                            || (importado.sintactico != null && importado.sintactico.hasErrors())
                            || (importado.semantico != null && importado.semantico.hasErrors())
                            || importado.abortadoPorDependencia || !importado.erroresImport.isEmpty())
                            && !resultado.abortadoPorDependencia) {
                        resultado.abortadoPorDependencia = true;
                        resultado.erroresImport.add("La dependencia '" + dependencia + "' contiene errores.");
                        cambio = true;
                    }
                }
            }
        } while (cambio);
        boolean proyectoValido = true;
        for (ResultadoAnalisis res : resultados)
            proyectoValido &= res.diagnosticos().isEmpty() && res.erroresImport.isEmpty() && !res.abortadoPorDependencia;
        for (ResultadoAnalisis res : resultados) {
            List<compiladorErrores> diagnosticos = res.diagnosticos();
            for (String mensaje : res.erroresImport) diagnosticos.add(new compiladorErrores(
                    compiladorErrores.TIPO_ERROR.SEMANTICO, 1, 1, "import", mensaje, "Corrige la dependencia."));
            res.resultadoSemantico = new ResultadoSemantico(res.sintactico == null ? null : res.sintactico.getArbol(),
                    res.semantico == null ? null : res.semantico.getAmbitoGlobal(), diagnosticos,
                    proyectoValido && res.semantico != null && res.semantico.getResultado().esExitoso());
        }
        return resultados;
    }

    /**
     * Carga y valida la sintaxis de las unidades antes del análisis semántico conjunto.
     */
    static boolean analizarArchivoConImports(Path archivo,
                                             List<ResultadoAnalisis> resultados,
                                             Set<Path> visitados,
                                             boolean validarImportsLocales) throws IOException {
        return analizarArchivoConImports(archivo, resultados, visitados, validarImportsLocales, null);
    }

    private static boolean analizarArchivoConImports(Path archivo, List<ResultadoAnalisis> resultados,
            Set<Path> visitados, boolean validarImportsLocales, String fuenteEditor) throws IOException {
        Path normalizado = archivo.toAbsolutePath().normalize();
        if (!visitados.add(normalizado)) return true;

        String source = fuenteEditor == null ? Files.readString(normalizado) : fuenteEditor;

        // Primero se valida el archivo completo. Solo un import aceptado por
        // la CFG puede convertirse después en una dependencia local.
        Lexico lexicoFinal = new Lexico(source);
        lexicoFinal.tokenizar();
        ResultadoAnalisis resultadoActual = new ResultadoAnalisis(normalizado, source, lexicoFinal);

        if (lexicoFinal.hasErrors()) {
            resultados.add(resultadoActual);
            return false;
        }

        AnalizadorSintactico sintactico = new AnalizadorSintactico(lexicoFinal.getTokens(), new Gramatica());
        sintactico.analizar();
        resultadoActual.sintactico = sintactico;
        List<Importacion> importaciones = new ArrayList<>();
        for (NodoArbolSintactico nodo : sintactico.getArbol().getHijos()) {
            if (!"IMPORTACION".equals(nodo.getNombre()) || nodo.esSintaxisIncompleta()) continue;
            for (NodoArbolSintactico hijo : nodo.getHijos()) {
                if ("RUTA".equals(hijo.getNombre()))
                    importaciones.add(new Importacion(hijo.getValor(), hijo.getLinea(), hijo.getColumna()));
            }
        }
        List<ResultadoAnalisis> dependencias = new ArrayList<>();
        boolean dependenciasOk = true;

        if (validarImportsLocales) {
            // Paso B: Resolver y validar cada dependencia
            for (Importacion imp : importaciones) {
            if ("runtime.Consola".equals(imp.nombre)) continue;
            Path rutaImportado = resolverImport(normalizado, imp.nombre);
            if (rutaImportado == null && esImportExterno(imp.nombre)) {
                continue;
            }

            if (rutaImportado == null) {
                resultadoActual.erroresImport.add(String.format(
                        "Línea %d: No se encontró la clase '%s'.", imp.linea, imp.nombre));
                dependenciasOk = false;
            } else {
                if (!validarIdentidadArchivo(rutaImportado, imp.nombre)) {
                    int opt = JOptionPane.showConfirmDialog(null,
                            "El archivo '" + rutaImportado.getFileName() + "' no coincide con el nombre '" + imp.nombre + "'.\n" +
                                    "¿Deseas usarlo de todos modos?", "Aviso de Identidad", JOptionPane.YES_NO_OPTION);
                    if (opt != JOptionPane.YES_OPTION) {
                        resultadoActual.erroresImport.add("Importación rechazada por el usuario: " + imp.nombre);
                        dependenciasOk = false;
                        continue;
                    }
                }

                resultadoActual.dependencias.add(rutaImportado.toAbsolutePath().normalize());
                if (!analizarArchivoConImports(rutaImportado, dependencias, visitados, true)) {
                    resultadoActual.erroresImport.add("La dependencia '" + imp.nombre + "' contiene errores.");
                    dependenciasOk = false;
                }
            }
        }
        }

        resultados.addAll(dependencias);

        if (!dependenciasOk) {
            resultadoActual.abortadoPorDependencia = true;
            resultados.add(resultadoActual);
            return false;
        }

        resultados.add(resultadoActual);
        return !sintactico.hasErrors();
    }

    private static boolean esImportExterno(String nombreImport) {
        String nombre = nombreImport.toLowerCase(Locale.ROOT);
        return nombre.startsWith("java.")
                || nombre.startsWith("javax.")
                || nombre.startsWith("org.")
                || nombre.startsWith("com.")
                || nombre.startsWith("net.")
                || nombre.startsWith("edu.");
    }

    private static Path resolverImport(Path archivoOrigen, String nombreImport) {
        Path carpeta = archivoOrigen.getParent();
        if (carpeta == null) carpeta = Paths.get(".");

        String nombreRuta = nombreImport.replace('.', File.separatorChar);
        String[] extensiones = {".txt", ".java", ".cpp", ""};

        for (String ext : extensiones) {
            Path candidato = carpeta.resolve(nombreRuta + ext).normalize();
            if (Files.isRegularFile(candidato)) return candidato;
        }

        // Los archivos locales tienen prioridad incluso bajo prefijos habituales
        // de bibliotecas. Si no existen, esos imports siguen siendo externos.
        if (esImportExterno(nombreImport)) return null;

        if (!GraphicsEnvironment.isHeadless()) {
            int resp = JOptionPane.showConfirmDialog(null,
                    "No se encontró '" + nombreImport + "'. ¿Buscar manualmente?",
                    "Import faltante", JOptionPane.YES_NO_OPTION);

            if (resp == JOptionPane.YES_OPTION) {
                JFileChooser buscador = new JFileChooser(carpeta.toFile());
                buscador.setDialogTitle("Localizar: " + nombreImport);
                if (buscador.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    return buscador.getSelectedFile().toPath();
                }
            }
        }
        return null;
    }

    private static boolean validarIdentidadArchivo(Path ruta, String nombreClase) {
        String baseName = nombreClase.contains(".")
                ? nombreClase.substring(nombreClase.lastIndexOf('.') + 1)
                : nombreClase;
        String fileName = ruta.getFileName().toString().toLowerCase();
        return fileName.contains(baseName.toLowerCase());
    }

    static void imprimirResultados(List<ResultadoAnalisis> resultados) {
        System.out.print(generarReporte(resultados));
    }

    static String generarReporte(List<ResultadoAnalisis> resultados) {
        int erroresTotales = 0;
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator()).append("=".repeat(60)).append(System.lineSeparator());
        sb.append("   REPORTE DEL COMPILADOR").append(System.lineSeparator());
        sb.append("=".repeat(60)).append(System.lineSeparator());

        for (ResultadoAnalisis res : resultados) {
            sb.append(System.lineSeparator()).append("ARCHIVO: ").append(res.archivo.getFileName()).append(System.lineSeparator());

            if (res.abortadoPorDependencia) {
                sb.append("[!] DEPENDENCIAS CON ERRORES.").append(System.lineSeparator());
                for (String e : res.erroresImport) {
                    sb.append("    -> ").append(e).append(System.lineSeparator());
                    erroresTotales++;
                }
            }

            sb.append("ESTADO: ").append(res.estadoDiagnosticos()).append(System.lineSeparator());
            if (res.sintactico != null && res.sintactico.hasErrors())
                sb.append("Análisis semántico parcial: se omiten los fragmentos con sintaxis incompleta.\n");
            sb.append("-- DIAGNOSTICOS COMBINADOS --").append(System.lineSeparator());
            for (compiladorErrores diagnostico : res.diagnosticos()) {
                sb.append(diagnostico).append(System.lineSeparator());
                erroresTotales++;
            }
            sb.append("-- TOKENS --").append(System.lineSeparator());
            for (Token t : res.lexico.getTokens()) {
                if (t.tipo != tipoToken.EOF) sb.append(t).append(System.lineSeparator());
            }

            if (res.sintactico != null) {
                sb.append("-- ARBOL SINTACTICO CON ACCIONES --").append(System.lineSeparator());
                sb.append(res.sintactico.getArbol().imprimirConAcciones());
                if (res.semantico != null) {
                    sb.append("-- ARBOL SEMANTICO --").append(System.lineSeparator());
                    sb.append(res.sintactico.getArbol().imprimirSemantico());
                }

                if (!res.tablaSimbolos().isEmpty()) {
                    sb.append("-- TABLA DE SIMBOLOS --").append(System.lineSeparator());
                    for (EntradaTablaSimbolos s : res.tablaSimbolos()) {
                        sb.append(String.format("%-18s %-14s %-12s L:%d C:%d%n",
                                s.nombre, s.tipo, s.rol, s.linea, s.columna));
                    }
                }
            }
        }

        sb.append(System.lineSeparator()).append("TOTAL DE ERRORES: ").append(erroresTotales).append(System.lineSeparator());
        sb.append("=".repeat(60)).append(System.lineSeparator());
        return sb.toString();
    }

    static class ResultadoAnalisis {
        final Path archivo;
        final String source;
        Lexico lexico;
        AnalizadorSintactico sintactico;
        Semantico semantico;
        ResultadoSemantico resultadoSemantico;
        boolean abortadoPorDependencia = false;
        final List<String> erroresImport = new ArrayList<>();
        final List<Path> dependencias = new ArrayList<>();

        List<EntradaTablaSimbolos> tablaSimbolos() {
            return semantico != null ? semantico.getTablaSimbolos()
                    : sintactico != null ? sintactico.getTablaSimbolos() : Collections.emptyList();
        }

        List<compiladorErrores> diagnosticos() {
            List<compiladorErrores> lista = new ArrayList<>(lexico.getErrors());
            if (sintactico != null) lista.addAll(sintactico.getErrores());
            if (semantico != null) lista.addAll(semantico.getErrores());
            lista.sort(Comparator.comparingInt((compiladorErrores e) -> e.linea)
                    .thenComparingInt(e -> e.columna).thenComparing(e -> e.TIPOERROR));
            return lista;
        }

        String estadoDiagnosticos() {
            List<String> fases = new ArrayList<>();
            if (lexico.hasErrors()) fases.add("LEXICOS");
            if (sintactico != null && sintactico.hasErrors()) fases.add("SINTACTICOS");
            if (semantico != null && semantico.hasErrors()) fases.add("SEMANTICOS");
            if (!erroresImport.isEmpty()) fases.add("IMPORTS");
            return fases.isEmpty() ? "SIN ERRORES" : "ERRORES " + String.join(" + ", fases);
        }

        ResultadoAnalisis(Path archivo, String source, Lexico lexico) {
            this.archivo = archivo; this.source = source; this.lexico = lexico;
        }
    }
}
