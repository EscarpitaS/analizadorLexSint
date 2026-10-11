import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.tree.DefaultMutableTreeNode;

class AnalizadorLexicoUI extends JFrame {
    private static final Color FONDO = new Color(245, 245, 245);
    private static final Color BORDE = new Color(215, 215, 215);

    private Path archivoActual;
    private final JLabel estadoLabel = new JLabel("Escribe código o abre un archivo.");
    private final JLabel archivosValor = crearValorResumen("0");
    private final JLabel tokensValor = crearValorResumen("0");
    private final JLabel erroresValor = crearValorResumen("0");
    private final JTabbedPane pestanas = new JTabbedPane();
    private final JTabbedPane espacioTrabajo = new JTabbedPane();
    private final EditorCodigo editor = new EditorCodigo();
    private final JButton analizarButton = crearBotonPrincipal("Analizar · F5");
    private final JButton ampliarResultadosButton = crearBotonSecundario("Ampliar resultados");
    private final JButton guardarReporteButton = crearBotonSecundario("Guardar reporte");
    private final JButton ejemploButton = crearBotonSecundario("Abrir ejemplo");

    private JCheckBox validarImportsCheck;

    private boolean resultadosAmpliados = false;
    private int estadoVentanaNormal = Frame.NORMAL;
    private List<Main.ResultadoAnalisis> ultimosResultados = new ArrayList<>();
    private boolean analizando;
    private long solicitudAnalisis;
    private long revisionAnalizada = -1;
    private Path archivoAnalizado;
    private VisorIr visorIr;
    private VisorNativo visorNativo;

    AnalizadorLexicoUI() {
        super("Lexer / Parser");
        configurarLookAndFeel();
        SwingUtilities.updateComponentTreeUI(editor);
        configurarVentana();
    }

    void mostrar(Path archivoInicial) {
        setVisible(true);
        if (archivoInicial != null) {
            cargarArchivo(archivoInicial);
        }
    }

    private void configurarLookAndFeel() {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {}
    }

    private void configurarVentana() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (confirmarCambiosPendientes()) {
                    if (visorNativo != null) visorNativo.cancelar();
                    dispose(); System.exit(0);
                }
            }
        });
        setMinimumSize(new Dimension(1120, 760));
        setPreferredSize(new Dimension(1220, 820));

        JPanel raiz = new JPanel(new BorderLayout(0, 8));
        raiz.setBackground(FONDO);
        raiz.setBorder(new EmptyBorder(12, 12, 12, 12));

        integrarControlesEditor();
        raiz.add(crearContenido(), BorderLayout.CENTER);

        EstiloInterfaz.aplicar(raiz);
        setContentPane(raiz);
        editor.configurarAcciones(this::nuevoArchivo, this::elegirArchivo,
                () -> guardarCodigo(false), () -> guardarCodigo(true));
        editor.alCambiar(this::actualizarEstadoEditor);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F5"), "analizar");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control ENTER"), "analizar");
        getRootPane().getActionMap().put("analizar", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { analizarEditor(); }
        });
        atajoGlobal("control S", () -> guardarCodigo(false));
        atajoGlobal("control shift S", () -> guardarCodigo(true));
        atajoGlobal("control N", this::nuevoArchivo);
        atajoGlobal("control O", this::elegirArchivo);
        limpiarResultados();
        actualizarEstadoEditor();
        pack();
        setLocationRelativeTo(null);
    }

    private void integrarControlesEditor() {
        validarImportsCheck = new JCheckBox("Validar imports locales");
        validarImportsCheck.setOpaque(false);
        validarImportsCheck.setForeground(Color.BLACK);
        validarImportsCheck.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        validarImportsCheck.setToolTipText("Busca archivos importados del proyecto. Déjalo desactivado para analizar un solo archivo.");
        validarImportsCheck.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JButton abrirButton = crearBotonSecundario("Abrir archivo");
        abrirButton.addActionListener(e -> elegirArchivo());
        analizarButton.addActionListener(e -> analizarEditor());
        validarImportsCheck.addActionListener(e -> marcarResultadosDesactualizados());
        ejemploButton.addActionListener(e -> abrirEjemplo());
        guardarReporteButton.addActionListener(e -> guardarReporte());
        guardarReporteButton.setEnabled(false);

        editor.integrarAnalisis(abrirButton, ejemploButton,
                new BarraAnalisis(validarImportsCheck, analizarButton, guardarReporteButton,
                        archivosValor, tokensValor, erroresValor), estadoLabel);
    }

    private void analizarEditor() {
        if (analizando) return;
        final Path ruta = archivoActual == null ? Paths.get("Sin_titulo.txt").toAbsolutePath().normalize() : archivoActual;
        final Path identidad = archivoActual;
        final String fuente = editor.getTexto();
        final long revision = editor.getRevision();
        final long solicitud = ++solicitudAnalisis;
        if (visorNativo != null) visorNativo.marcarDesactualizado();
        analizando = true;
        analizarButton.setEnabled(false);
        guardarReporteButton.setEnabled(false);
        estadoLabel.setForeground(Color.BLACK);
        estadoLabel.setText("Analizando el contenido del editor...");

        final boolean validarImports = identidad != null && validarImportsCheck.isSelected();

        SwingWorker<AnalisisConIr, Void> worker = new SwingWorker<>() {
            @Override
            protected AnalisisConIr doInBackground() throws Exception {
                List<Main.ResultadoAnalisis> unidades = Main.analizarProyecto(ruta, fuente, validarImports);
                return new AnalisisConIr(unidades, VisorIr.generar(unidades, ruta));
            }

            @Override
            protected void done() {
                if (solicitud != solicitudAnalisis) return;
                analizando = false;
                analizarButton.setEnabled(true);
                if (revision != editor.getRevision() || !java.util.Objects.equals(identidad, editor.getArchivo())
                        || validarImports != (editor.getArchivo() != null && validarImportsCheck.isSelected())) {
                    estadoLabel.setText("El código cambió durante el análisis. Los resultados están desactualizados.");
                    return;
                }
                try {
                    revisionAnalizada = revision;
                    archivoAnalizado = identidad;
                    mostrarResultados(get());
                    espacioTrabajo.setTitleAt(1, "Resultados");
                    espacioTrabajo.setSelectedIndex(1);
                } catch (Exception e) {
                    mostrarErrorLectura(e);
                }
            }
        };
        worker.execute();
    }

    private static final class AnalisisConIr {
        final List<Main.ResultadoAnalisis> unidades;
        final VisorIr.Resultado ir;
        AnalisisConIr(List<Main.ResultadoAnalisis> unidades, VisorIr.Resultado ir) {
            this.unidades = unidades;
            this.ir = ir;
        }
    }

    private JComponent crearContenido() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE),
                new EmptyBorder(10, 10, 10, 10)));

        pestanas.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JPanel barraResultados = new JPanel(new BorderLayout(12, 0));
        barraResultados.setBackground(Color.WHITE);
        barraResultados.setBorder(new EmptyBorder(0, 0, 10, 0));

        JLabel tituloResultados = new JLabel("Resultados del análisis");
        tituloResultados.setFont(new Font("Segoe UI", Font.BOLD, 16));
        tituloResultados.setForeground(Color.BLACK);

        ampliarResultadosButton.addActionListener(e -> alternarVistaResultados());
        JPanel accionesResultados = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        accionesResultados.setBackground(Color.WHITE);
        accionesResultados.add(ampliarResultadosButton);

        barraResultados.add(tituloResultados, BorderLayout.WEST);
        barraResultados.add(accionesResultados, BorderLayout.EAST);

        JPanel resultados = new JPanel(new BorderLayout());
        resultados.add(barraResultados, BorderLayout.NORTH);
        resultados.add(pestanas, BorderLayout.CENTER);
        espacioTrabajo.addTab("Editor", editor);
        espacioTrabajo.addTab("Resultados", resultados);
        espacioTrabajo.setFont(new Font("Segoe UI", Font.BOLD, 14));
        panel.add(espacioTrabajo, BorderLayout.CENTER);
        return panel;
    }

    private void alternarVistaResultados() {
        resultadosAmpliados = !resultadosAmpliados;
        if (resultadosAmpliados) {
            estadoVentanaNormal = getExtendedState();
            ampliarResultadosButton.setText("Vista normal");
            setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
        } else {
            ampliarResultadosButton.setText("Ampliar resultados");
            setExtendedState(estadoVentanaNormal);
        }
        revalidate(); repaint();
    }

    private static JLabel crearValorResumen(String texto) {
        JLabel label = new JLabel(texto);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        return label;
    }

    private JButton crearBotonPrincipal(String texto) {
        JButton boton = new JButton(texto);
        boton.setBackground(new Color(225, 225, 225));
        boton.setForeground(Color.BLACK);
        boton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        boton.setFocusPainted(false);
        boton.setBorder(new EmptyBorder(7, 10, 7, 10));
        return boton;
    }

    private JButton crearBotonSecundario(String texto) {
        JButton boton = new JButton(texto);
        boton.setBackground(new Color(250, 250, 250));
        boton.setForeground(Color.BLACK);
        boton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        boton.setFocusPainted(false);
        boton.setBorder(new EmptyBorder(7, 10, 7, 10));
        return boton;
    }

    private void elegirArchivo() {
        JFileChooser selector = new JFileChooser();
        EstiloInterfaz.aplicar(selector);
        selector.setDialogTitle("Selecciona el archivo de código fuente");
        selector.setPreferredSize(new Dimension(900, 580));
        selector.setFileFilter(new FileNameExtensionFilter(
                "Archivos de código fuente (.txt, .java, .cpp)", "txt", "java", "cpp"));
        if (archivoActual != null && archivoActual.getParent() != null) {
            selector.setCurrentDirectory(archivoActual.getParent().toFile());
        }
        if (selector.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            if (confirmarCambiosPendientes()) cargarArchivo(selector.getSelectedFile().toPath());
        }
    }

    private void abrirEjemplo() {
        Path ejemplo;
        try {
            ejemplo = localizarEjemploDemo();
        } catch (IOException e) {
            mostrarErrorLectura(e);
            return;
        }
        if (Files.isRegularFile(ejemplo)) {
            if (!confirmarCambiosPendientes()) return;
            try {
                String fuente = Files.readString(ejemplo, StandardCharsets.UTF_8);
                solicitudAnalisis++;
                analizando = false;
                limpiarResultados();
                editor.cargar(null, fuente);
                espacioTrabajo.setSelectedIndex(0);
                estadoLabel.setForeground(Color.BLACK);
                estadoLabel.setText("Ejemplo cargado como documento nuevo. Guarda para crear tu copia.");
                editor.enfocar();
            } catch (IOException e) { mostrarErrorLectura(e); }
            return;
        }

        JOptionPane.showMessageDialog(this,
                "No se encontró el archivo de ejemplo en:\n" + ejemplo,
                "Ejemplo no disponible",
                JOptionPane.WARNING_MESSAGE);
    }

    static Path localizarEjemploDemo() throws IOException {
        Path[] candidatos = {
                Paths.get("untitled", "ejemplos", "programa_demo.txt"),
                Paths.get("ejemplos", "programa_demo.txt")
        };

        for (Path candidato : candidatos) {
            Path normalizado = candidato.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalizado)) return normalizado;
        }
        // El JAR lleva el ejemplo incorporado y funciona desde cualquier carpeta.
        try (java.io.InputStream recurso = AnalizadorLexicoUI.class.getResourceAsStream("/ejemplos/programa_demo.txt")) {
            if (recurso != null) {
                Path extraido = Files.createTempFile("analizador-demo-", ".txt");
                extraido.toFile().deleteOnExit();
                Files.copy(recurso, extraido, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return extraido;
            }
        }
        return candidatos[0].toAbsolutePath().normalize();
    }

    private void guardarReporte() {
        if (ultimosResultados.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Primero realiza un análisis.");
            return;
        }

        JFileChooser selector = new JFileChooser();
        EstiloInterfaz.aplicar(selector);
        selector.setDialogTitle("Guardar reporte del análisis");
        selector.setSelectedFile(new java.io.File("reporte_analizador.txt"));
        selector.setFileFilter(new FileNameExtensionFilter("Reporte de texto (.txt)", "txt"));

        if (selector.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path destino = selector.getSelectedFile().toPath();
        if (!destino.getFileName().toString().toLowerCase().endsWith(".txt")) {
            destino = destino.resolveSibling(destino.getFileName() + ".txt");
        }

        try {
            Files.writeString(destino, Main.generarReporte(ultimosResultados), StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "Reporte guardado en:\n" + destino);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo guardar el reporte:\n" + e.getMessage(),
                    "Error al guardar",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void mostrarResultados(AnalisisConIr analisis) {
        List<Main.ResultadoAnalisis> resultados = analisis.unidades;
        ultimosResultados = new ArrayList<>(resultados);
        guardarReporteButton.setEnabled(!ultimosResultados.isEmpty());
        if (visorNativo != null) visorNativo.cancelar();
        Path principal = archivoActual == null ? Paths.get("Sin_titulo.txt").toAbsolutePath().normalize() : archivoActual;
        ProyectoNativo proyecto = new ProyectoNativo(analisis.ir, resultados, principal);
        ResumenResultados resumen = llenarPestanas(pestanas, resultados,
                resultado -> crearPanelArchivo(resultado, tokensSinEOF(resultado.lexico.getTokens())), analisis.ir, proyecto);
        visorIr = (VisorIr) pestanas.getComponentAt(resultados.size());
        visorNativo = (VisorNativo) pestanas.getComponentAt(resultados.size() + 1);
        visorNativo.alCambiarDependencia(this::marcarResultadosDesactualizados);
        int totalTokens = resumen.tokens;
        int totalErrores = resumen.errores;

        archivosValor.setText(String.valueOf(resultados.size()));
        tokensValor.setText(String.valueOf(totalTokens));
        erroresValor.setText(String.valueOf(totalErrores));

        if (totalErrores == 0) {
            estadoLabel.setForeground(Color.BLACK);
            estadoLabel.setText(analisis.ir.esExitoso() ? "Análisis completado sin errores. IR generada y verificada."
                    : "Análisis completado. Consulta los diagnósticos en IR del proyecto.");
        } else {
            estadoLabel.setForeground(Color.BLACK);
            estadoLabel.setText("Análisis completado con " + totalErrores + " error(es).");
        }
    }

    static ResumenResultados llenarPestanas(JTabbedPane tabs, List<Main.ResultadoAnalisis> resultados,
            java.util.function.Function<Main.ResultadoAnalisis, JComponent> crearPanel,
            VisorIr.Resultado ir, ProyectoNativo proyecto) {
        ResumenResultados resumen = llenarPestanas(tabs, resultados, crearPanel, ir);
        tabs.addTab("ASM y ejecutable", new VisorNativo(proyecto));
        return resumen;
    }

    /** Presentación verificable sin construir una ventana ni requerir una pantalla. */
    static ResumenResultados llenarPestanas(JTabbedPane pestanas, List<Main.ResultadoAnalisis> resultados,
            java.util.function.Function<Main.ResultadoAnalisis, JComponent> crearPanel) {
        pestanas.removeAll();
        int totalTokens = 0, totalErrores = 0;
        for (Main.ResultadoAnalisis resultado : resultados) {
            totalTokens += tokensSinEOF(resultado.lexico.getTokens()).size();
            totalErrores += crearErrores(resultado).size();
            String titulo = (resultado.abortadoPorDependencia ? "⚠ " : "") + resultado.archivo.getFileName();
            pestanas.addTab(titulo, crearPanel.apply(resultado));
            if (resultado.abortadoPorDependencia) pestanas.setForegroundAt(pestanas.getTabCount() - 1, Color.BLACK);
        }
        return new ResumenResultados(totalTokens, totalErrores);
    }

    static final class ResumenResultados {
        final int tokens;
        final int errores;
        ResumenResultados(int tokens, int errores) { this.tokens = tokens; this.errores = errores; }
    }

    static ResumenResultados llenarPestanas(JTabbedPane pestanas, List<Main.ResultadoAnalisis> resultados,
            java.util.function.Function<Main.ResultadoAnalisis, JComponent> crearPanel, VisorIr.Resultado ir) {
        ResumenResultados resumen = llenarPestanas(pestanas, resultados, crearPanel);
        pestanas.addTab("IR del proyecto", new VisorIr(ir));
        return resumen;
    }

    private JComponent crearPanelArchivo(Main.ResultadoAnalisis resultado, List<Token> tokensVisibles) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        List<ErrorFila> errores = crearErrores(resultado);
        JTable tablaTokens = crearTablaTokens(tokensVisibles);
        JTable tablaErrores = crearTablaErrores(errores);
        NavegacionErrores.conectar(tablaErrores, fila -> irAlError(resultado, errores.get(fila)));

        JTabbedPane vistasArchivo = new JTabbedPane();
        vistasArchivo.addTab("Tokens", crearScrollConTitulo("Lista de tokens", tablaTokens));
        vistasArchivo.addTab("Errores (" + errores.size() + ")", crearPanelErrores(tablaErrores, errores.size()));
        vistasArchivo.addTab("AST sintáctico", crearPanelArbol(resultado, false));
        vistasArchivo.addTab("AST semántico", crearPanelArbol(resultado, true));
        vistasArchivo.addTab("Tabla de símbolos", crearPanelTablaSimbolos(resultado));
        vistasArchivo.addTab("Gramática", crearPanelGramatica());
        vistasArchivo.addTab("Código", crearPanelCodigo(resultado.source, errores));
        vistasArchivo.addTab("Reporte", crearPanelReporte(resultado, errores));

        if (resultado.abortadoPorDependencia) {
            JLabel aviso = new JLabel("Dependencias con errores: se muestran los resultados disponibles; el proyecto no es válido.");
            aviso.setForeground(Color.BLACK);
            panel.add(aviso, BorderLayout.NORTH);
        }
        panel.add(vistasArchivo, BorderLayout.CENTER);
        EstiloInterfaz.aplicar(panel);
        return panel;
    }

    private void irAlError(Main.ResultadoAnalisis resultado, ErrorFila error) {
        if (error.linea < 1) {
            JOptionPane.showMessageDialog(this, "Este diagnóstico no tiene una ubicación en el código.");
            return;
        }
        Path ruta = resultado.archivo.toAbsolutePath().normalize();
        boolean mismoDocumento = java.util.Objects.equals(ruta, editor.getArchivo())
                || (editor.getArchivo() == null && archivoAnalizado == null
                    && !ultimosResultados.isEmpty() && resultado == ultimosResultados.get(0));
        if (!mismoDocumento) {
            if (!confirmarCambiosPendientes()) return;
            cargarArchivo(ruta);
            if (!java.util.Objects.equals(ruta, editor.getArchivo())) return;
        }
        if (!resultado.source.equals(editor.getTexto())) {
            JOptionPane.showMessageDialog(this,
                    "El código cambió desde el análisis. La ubicación del error puede estar desactualizada.");
            return;
        }
        if (resultadosAmpliados) alternarVistaResultados();
        espacioTrabajo.setSelectedIndex(0);
        editor.irAUbicacion(error.linea, error.columna);
    }

    private JComponent crearPanelArbol(Main.ResultadoAnalisis resultado, boolean semantico) {
        if (resultado.sintactico == null || resultado.sintactico.getArbol() == null) {
            return new JLabel(" El análisis sintáctico se omite si existen errores léxicos.");
        }

        return new VisorAst(resultado.sintactico.getArbol(), semantico,
                resultado.source, resultado.sintactico.hasErrors());
    }

    static DefaultMutableTreeNode crearNodoSwing(NodoArbolSintactico nodo, boolean semantico) {
        DefaultMutableTreeNode actual = new DefaultMutableTreeNode(nodo.getTextoVista(semantico));
        for (String accion : nodo.getAccionesSemanticas()) {
            actual.add(new DefaultMutableTreeNode("Acción: " + accion));
        }
        for (NodoArbolSintactico hijo : semantico ? nodo.getHijosSemanticos() : nodo.getHijos()) {
            actual.add(crearNodoSwing(hijo, semantico));
        }
        return actual;
    }

    private JComponent crearPanelReporte(Main.ResultadoAnalisis resultado, List<ErrorFila> errores) {
        JTextArea reporte = new JTextArea(construirReporteArchivo(resultado, errores));
        reporte.setEditable(false);
        reporte.setFont(new Font("Consolas", Font.PLAIN, 13));
        reporte.setBackground(new Color(250, 250, 250));
        reporte.setCaretPosition(0);
        return new JScrollPane(reporte);
    }

    private String construirReporteArchivo(Main.ResultadoAnalisis resultado, List<ErrorFila> errores) {
        StringBuilder sb = new StringBuilder();
        sb.append("ARCHIVO: ").append(resultado.archivo).append(System.lineSeparator());
        sb.append("ESTADO: ").append(resultado.estadoDiagnosticos()).append(System.lineSeparator());
        if (resultado.sintactico != null && resultado.sintactico.hasErrors())
            sb.append("Análisis semántico parcial: se omiten los fragmentos con sintaxis incompleta.\n");
        sb.append("TOKENS: ").append(tokensSinEOF(resultado.lexico.getTokens()).size()).append(System.lineSeparator());
        sb.append("ERRORES: ").append(errores.size()).append(System.lineSeparator()).append(System.lineSeparator());

        if (!errores.isEmpty()) {
            sb.append("ERRORES Y SUGERENCIAS").append(System.lineSeparator());
            for (ErrorFila e : errores) {
                sb.append("- ").append(e.tipo).append(" ").append(e.ubicacion)
                        .append(" en '").append(e.lexema).append("': ")
                        .append(e.mensaje).append(System.lineSeparator());
                if (e.sugerencia != null && !e.sugerencia.equals("-")) {
                    sb.append("  Sugerencia: ").append(e.sugerencia).append(System.lineSeparator());
                }
            }
            sb.append(System.lineSeparator());
        }

        if (resultado.sintactico != null && resultado.sintactico.getArbol() != null) {
            sb.append("ÁRBOL SINTÁCTICO CON ACCIONES").append(System.lineSeparator());
            sb.append(resultado.sintactico.getArbol().imprimirConAcciones()).append(System.lineSeparator());
            if (resultado.semantico != null) {
                sb.append("ÁRBOL SEMÁNTICO").append(System.lineSeparator());
                sb.append(resultado.sintactico.getArbol().imprimirSemantico()).append(System.lineSeparator());
            }

            if (!resultado.tablaSimbolos().isEmpty()) {
                sb.append("TABLA DE SÍMBOLOS").append(System.lineSeparator());
                for (EntradaTablaSimbolos s : resultado.tablaSimbolos()) {
                    sb.append(String.format("%-18s %-14s %-12s L:%d C:%d%n",
                            s.nombre, s.tipo, s.rol, s.linea, s.columna));
                }
            }
        }

        return sb.toString();
    }

    private JComponent crearPanelTablaSimbolos(Main.ResultadoAnalisis resultado) {
        if (resultado.sintactico == null) {
            return new JLabel(" No hay tabla de símbolos disponible.");
        }

        JTable tabla = new JTable(new SimboloTableModel(resultado.tablaSimbolos()));
        configurarTablaBase(tabla);
        ajustarColumnas(tabla, 220, 120, 130, 80, 80);
        return crearScrollConTitulo("Símbolos semánticos y ámbitos persistentes", tabla);
    }

    private JComponent crearPanelGramatica() {
        Gramatica gramatica = new Gramatica();
        JTable tabla = new JTable(new GramaticaTableModel(gramatica.construirTablaGramatical()));
        configurarTablaBase(tabla);
        ajustarColumnas(tabla, 240, 80, 420);

        JTextArea producciones = new JTextArea(String.join(System.lineSeparator(), gramatica.getProducciones()));
        producciones.setEditable(false);
        producciones.setFont(new Font("Consolas", Font.PLAIN, 13));
        producciones.setBackground(new Color(250, 250, 250));
        producciones.setCaretPosition(0);

        JTabbedPane pestañasGramatica = new JTabbedPane();
        pestañasGramatica.addTab("Símbolos", crearScrollConTitulo("VT, VN y símbolo inicial", tabla));
        pestañasGramatica.addTab("Producciones BNF", new JScrollPane(producciones));
        return pestañasGramatica;
    }

    private JTable crearTablaTokens(List<Token> tokensVisibles) {
        JTable tabla = new JTable(new TokenTableModel(tokensVisibles));
        configurarTablaBase(tabla);
        tabla.setDefaultRenderer(Object.class, new TokenRenderer());
        ajustarColumnas(tabla, 50, 150, 400, 80, 80);
        return tabla;
    }

    private JTable crearTablaErrores(List<ErrorFila> errores) {
        JTable tabla = new JTable(new ErrorTableModel(errores));
        configurarTablaBase(tabla);
        tabla.setRowHeight(50);
        tabla.setDefaultRenderer(Object.class, new ErrorRenderer());
        ajustarColumnas(tabla, 100, 150, 150, 300, 500);
        return tabla;
    }

    private void configurarTablaBase(JTable tabla) {
        tabla.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        tabla.setFillsViewportHeight(true);
        tabla.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    }

    private void ajustarColumnas(JTable tabla, int... anchos) {
        TableColumnModel columnas = tabla.getColumnModel();
        for (int i = 0; i < anchos.length && i < columnas.getColumnCount(); i++) {
            columnas.getColumn(i).setPreferredWidth(anchos[i]);
        }
    }

    private JComponent crearPanelErrores(JTable tablaErrores, int totalErrores) {
        if (totalErrores == 0) {
            return new JLabel(" Sin errores detectados.");
        }
        return crearScrollConTitulo("Errores y sugerencias", tablaErrores);
    }

    private JComponent crearPanelCodigo(String source, List<ErrorFila> errores) {
        JTextPane codigo = new JTextPane();
        codigo.setEditable(false);
        codigo.setFont(new Font("Consolas", Font.PLAIN, 14));
        Set<Integer> lineasError = obtenerLineasConError(errores);
        cargarCodigoConResaltado(codigo, source, lineasError);
        return new JScrollPane(codigo);
    }

    private void cargarCodigoConResaltado(JTextPane codigo, String source, Set<Integer> lineasError) {
        StyledDocument documento = codigo.getStyledDocument();
        String[] lineas = source.split("\\R", -1);
        try {
            for (int i = 0; i < lineas.length; i++) {
                SimpleAttributeSet estilo = new SimpleAttributeSet();
                if (lineasError.contains(i + 1)) StyleConstants.setBackground(estilo, new Color(230, 230, 230));
                documento.insertString(documento.getLength(), String.format("%4d | %s%n", i + 1, lineas[i]), estilo);
            }
        } catch (BadLocationException ignored) {}
    }

    private JScrollPane crearScrollConTitulo(String titulo, JTable tabla) {
        JScrollPane scroll = new JScrollPane(tabla);
        scroll.setBorder(BorderFactory.createTitledBorder(titulo));
        return scroll;
    }

    private void limpiarResultados() {
        if (visorNativo != null) visorNativo.cancelar();
        visorNativo = null;
        visorIr = null;
        pestanas.removeAll();
        ultimosResultados.clear();
        guardarReporteButton.setEnabled(false);
        revisionAnalizada = -1;
        archivosValor.setText("0"); tokensValor.setText("0"); erroresValor.setText("0");
        if (espacioTrabajo.getTabCount() > 1) espacioTrabajo.setTitleAt(1, "Resultados");
        pestanas.addTab("Inicio", new JLabel(" Escribe o abre código en el editor."));
    }

    private void cargarArchivo(Path ruta) {
        try {
            String fuente = Files.readString(ruta, StandardCharsets.UTF_8);
            solicitudAnalisis++;
            analizando = false;
            limpiarResultados();
            editor.cargar(ruta, fuente);
            espacioTrabajo.setSelectedIndex(0);
            estadoLabel.setText("Archivo cargado. Edita el código.");
            editor.enfocar();
        } catch (IOException e) { mostrarErrorLectura(e); }
    }

    private void nuevoArchivo() {
        if (!confirmarCambiosPendientes()) return;
        solicitudAnalisis++;
        analizando = false;
        limpiarResultados();
        editor.cargar(null, "");
        espacioTrabajo.setSelectedIndex(0);
        estadoLabel.setText("Documento nuevo. Escribe código.");
        editor.enfocar();
    }

    private void atajoGlobal(String tecla, Runnable accion) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(tecla), tecla);
        getRootPane().getActionMap().put(tecla, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { accion.run(); }
        });
    }

    private boolean guardarCodigo(boolean como) {
        Path destino = editor.getArchivo();
        if (como || destino == null) {
            JFileChooser selector = new JFileChooser();
            EstiloInterfaz.aplicar(selector);
            selector.setDialogTitle("Guardar código fuente");
            selector.setSelectedFile(destino == null ? new java.io.File("programa.txt") : destino.toFile());
            selector.setFileFilter(new FileNameExtensionFilter("Código fuente (.txt, .java, .cpp)", "txt", "java", "cpp"));
            if (selector.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
            destino = selector.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (Files.exists(destino) && !destino.equals(editor.getArchivo())
                    && JOptionPane.showConfirmDialog(this, "El archivo ya existe. ¿Deseas reemplazarlo?", "Guardar código",
                            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return false;
        }
        try {
            editor.guardar(destino);
            estadoLabel.setForeground(Color.BLACK);
            estadoLabel.setText("Código guardado en " + destino + ".");
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar el código:\n" + e.getMessage(), "Error al guardar", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private boolean confirmarCambiosPendientes() {
        if (!editor.tieneCambios()) return true;
        int respuesta = JOptionPane.showOptionDialog(this, "El documento tiene cambios sin guardar.", "Cambios pendientes",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new String[] { "Guardar", "Descartar", "Cancelar" }, "Guardar");
        return respuesta == 0 ? guardarCodigo(false) : respuesta == 1;
    }

    private void actualizarEstadoEditor() {
        archivoActual = editor.getArchivo();
        setTitle((editor.tieneCambios() ? "* " : "") + (archivoActual == null ? "Sin título" : archivoActual.getFileName()) + " — Analizador léxico y sintáctico");
        analizarButton.setEnabled(!analizando);
        validarImportsCheck.setEnabled(archivoActual != null);
        validarImportsCheck.setToolTipText(archivoActual == null ? "Guarda el documento para resolver imports relativos a su carpeta."
                : "Busca archivos importados en la carpeta del documento.");
        if (revisionAnalizada >= 0 && (revisionAnalizada != editor.getRevision()
                || !java.util.Objects.equals(archivoAnalizado, archivoActual))) marcarResultadosDesactualizados();
    }

    private void marcarResultadosDesactualizados() {
        if (ultimosResultados.isEmpty()) return;
        if (visorIr != null) visorIr.marcarDesactualizado();
        if (visorNativo != null) visorNativo.marcarDesactualizado();
        espacioTrabajo.setTitleAt(1, "Resultados (desactualizados)");
        guardarReporteButton.setEnabled(false);
        if (!analizando) {
            estadoLabel.setForeground(Color.BLACK);
            estadoLabel.setText("El editor cambió. Los resultados corresponden al análisis anterior.");
        }
    }

    private void mostrarErrorLectura(Exception e) {
        JOptionPane.showMessageDialog(this, "Error al analizar: " + e.getMessage());
    }

    private static List<Token> tokensSinEOF(List<Token> tokens) {
        List<Token> visibles = new ArrayList<>();
        for (Token t : tokens) if (t.tipo != tipoToken.EOF) visibles.add(t);
        return visibles;
    }

    static List<ErrorFila> crearErrores(Main.ResultadoAnalisis resultado) {
        List<ErrorFila> errores = new ArrayList<>();
        for (String ei : resultado.erroresImport) errores.add(new ErrorFila("IMPORT", "-", "-", ei, "-", -1, -1));
        for (compiladorErrores e : resultado.diagnosticos()) {
            errores.add(new ErrorFila(e.TIPOERROR.toString(), "L:" + e.linea + " C:" + e.columna,
                    e.lexema, e.mensaje, e.pista, e.linea, e.columna));
        }
        return errores;
    }

    private Set<Integer> obtenerLineasConError(List<ErrorFila> errores) {
        Set<Integer> lineas = new LinkedHashSet<>();
        for (ErrorFila e : errores) if (e.linea > 0) lineas.add(e.linea);
        return lineas;
    }

    // --- CLASES INTERNAS DE SOPORTE ---
    private static class TokenTableModel extends AbstractTableModel {
        private final List<Token> tokens;
        TokenTableModel(List<Token> tokens) { this.tokens = tokens; }
        @Override public int getRowCount() { return tokens.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int c) {
            switch(c) {
                case 0: return "#"; case 1: return "Tipo"; case 2: return "Lexema";
                case 3: return "Línea"; case 4: return "Columna"; default: return "";
            }
        }
        @Override public Object getValueAt(int r, int c) {
            Token t = tokens.get(r);
            switch(c) {
                case 0: return r+1; case 1: return t.tipo; case 2: return t.lexema;
                case 3: return t.linea; case 4: return t.columna; default: return "";
            }
        }
    }

    private static class ErrorTableModel extends AbstractTableModel {
        private final List<ErrorFila> errores;
        ErrorTableModel(List<ErrorFila> errores) { this.errores = errores; }
        @Override public int getRowCount() { return errores.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int c) {
            switch(c) {
                case 0: return "Tipo"; case 1: return "Ubicación"; case 2: return "Lexema";
                case 3: return "Mensaje"; case 4: return "Sugerencia"; default: return "";
            }
        }
        @Override public Object getValueAt(int r, int c) {
            ErrorFila f = errores.get(r);
            switch(c) {
                case 0: return f.tipo; case 1: return f.ubicacion; case 2: return f.lexema;
                case 3: return f.mensaje; case 4: return f.sugerencia; default: return "";
            }
        }
    }

    private static class SimboloTableModel extends AbstractTableModel {
        private final List<EntradaTablaSimbolos> simbolos;
        SimboloTableModel(List<EntradaTablaSimbolos> simbolos) { this.simbolos = simbolos; }
        @Override public int getRowCount() { return simbolos.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int c) {
            switch(c) {
                case 0: return "Nombre"; case 1: return "Tipo"; case 2: return "Rol";
                case 3: return "Línea"; case 4: return "Columna"; default: return "";
            }
        }
        @Override public Object getValueAt(int r, int c) {
            EntradaTablaSimbolos s = simbolos.get(r);
            switch(c) {
                case 0: return s.nombre; case 1: return s.tipo; case 2: return s.rol;
                case 3: return s.linea; case 4: return s.columna; default: return "";
            }
        }
    }

    private static class GramaticaTableModel extends AbstractTableModel {
        private final List<String[]> filas;
        GramaticaTableModel(List<String[]> filas) { this.filas = filas; }
        @Override public int getRowCount() { return filas.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) {
            switch(c) {
                case 0: return "Símbolo"; case 1: return "Conjunto"; case 2: return "Descripción";
                default: return "";
            }
        }
        @Override public Object getValueAt(int r, int c) {
            return filas.get(r)[c];
        }
    }

    private static class ErrorFila {
        String tipo, ubicacion, lexema, mensaje, sugerencia; int linea, columna;
        ErrorFila(String t, String u, String lx, String m, String s, int l, int c) {
            tipo=t; ubicacion=u; lexema=lx; mensaje=m; sugerencia=s; linea=l; columna=c;
        }
    }

    private static class TokenRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            Component cp = super.getTableCellRendererComponent(t, v, s, f, r, c);
            if (!s) cp.setBackground(new Color(250, 250, 250));
            return cp;
        }
    }

    private static class ErrorRenderer extends JTextArea implements TableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            setText(v == null ? "" : v.toString());
            setLineWrap(true); setWrapStyleWord(true);
            setForeground(Color.BLACK);
            setBackground(s ? t.getSelectionBackground() : Color.WHITE);
            return this;
        }
    }
}
