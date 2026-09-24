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
    private static final Color FONDO = new Color(241, 245, 249);
    private static final Color AZUL_OSCURO = new Color(15, 23, 42);
    private static final Color AZUL = new Color(37, 99, 235);
    private static final Color VERDE = new Color(22, 163, 74);
    private static final Color ROJO = new Color(220, 38, 38);
    private static final Color BORDE = new Color(203, 213, 225);

    private Path archivoActual;
    private final JLabel archivoLabel = new JLabel("Ningun archivo seleccionado");
    private final JLabel estadoLabel = new JLabel("Selecciona un archivo para iniciar el analisis.");
    private final JLabel archivosValor = crearValorResumen("0");
    private final JLabel tokensValor = crearValorResumen("0");
    private final JLabel erroresValor = crearValorResumen("0");
    private final JTabbedPane pestanas = new JTabbedPane();
    private final JButton analizarButton = crearBotonSecundario("Analizar de nuevo");
    private final JButton ampliarResultadosButton = crearBotonSecundario("Ampliar resultados");
    private final JButton guardarReporteButton = crearBotonSecundario("Guardar reporte");
    private final JButton ejemploButton = crearBotonSecundario("Abrir ejemplo");

    private JCheckBox validarImportsCheck;

    private JPanel encabezadoPanel;
    private boolean resultadosAmpliados = false;
    private int estadoVentanaNormal = Frame.NORMAL;
    private List<Main.ResultadoAnalisis> ultimosResultados = new ArrayList<>();

    AnalizadorLexicoUI() {
        super("Lexer / Parser");
        configurarLookAndFeel();
        configurarVentana();
    }

    void mostrar(Path archivoInicial) {
        setVisible(true);
        if (archivoInicial != null) {
            analizarArchivo(archivoInicial);
        }
    }

    private void configurarLookAndFeel() {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {}
    }

    private void configurarVentana() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1120, 760));
        setPreferredSize(new Dimension(1220, 820));

        JPanel raiz = new JPanel(new BorderLayout(0, 16));
        raiz.setBackground(FONDO);
        raiz.setBorder(new EmptyBorder(18, 18, 18, 18));

        encabezadoPanel = crearEncabezado();
        raiz.add(encabezadoPanel, BorderLayout.NORTH);
        raiz.add(crearContenido(), BorderLayout.CENTER);

        setContentPane(raiz);
        limpiarResultados();
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel crearEncabezado() {
        JPanel contenedor = new JPanel(new BorderLayout(16, 16));
        contenedor.setOpaque(false);

        JPanel tituloPanel = new GradientPanel();
        tituloPanel.setLayout(new BorderLayout(12, 12));
        tituloPanel.setBorder(new EmptyBorder(24, 26, 24, 26));

        JLabel titulo = new JLabel("Analizador Lexico y Sintactico");
        titulo.setForeground(Color.WHITE);
        titulo.setFont(new Font("Segoe UI", Font.BOLD, 30));

        JLabel subtitulo = new JLabel("Analisis lexico, sintactico y semantico - Arboles y acciones");
        subtitulo.setForeground(new Color(219, 234, 254));
        subtitulo.setFont(new Font("Segoe UI", Font.PLAIN, 15));

        JPanel textos = new JPanel(new GridLayout(2, 1, 0, 3));
        textos.setOpaque(false);
        textos.add(titulo);
        textos.add(subtitulo);

        // --- CONFIGURACIÓN DE ACCIONES ---
        validarImportsCheck = new JCheckBox("Validar imports locales");
        validarImportsCheck.setOpaque(false);
        validarImportsCheck.setForeground(Color.WHITE);
        validarImportsCheck.setFont(new Font("Segoe UI", Font.BOLD, 13));
        validarImportsCheck.setToolTipText("Busca archivos importados del proyecto. Dejalo apagado para analizar un solo archivo.");
        validarImportsCheck.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JButton abrirButton = crearBotonPrincipal("Abrir archivo");
        abrirButton.addActionListener(e -> elegirArchivo());

        analizarButton.addActionListener(e -> {
            if (archivoActual != null) analizarArchivo(archivoActual);
        });
        analizarButton.setEnabled(false);

        ejemploButton.addActionListener(e -> abrirEjemplo());

        guardarReporteButton.addActionListener(e -> guardarReporte());
        guardarReporteButton.setEnabled(false);

        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        acciones.setOpaque(false);
        acciones.add(validarImportsCheck);
        acciones.add(ejemploButton);
        acciones.add(abrirButton);
        acciones.add(analizarButton);
        acciones.add(guardarReporteButton);

        tituloPanel.add(textos, BorderLayout.CENTER);
        tituloPanel.add(acciones, BorderLayout.EAST);

        // --- DETALLE Y RESUMEN ---
        JPanel detalleArchivo = new JPanel(new BorderLayout(8, 4));
        detalleArchivo.setOpaque(false);
        archivoLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        estadoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        estadoLabel.setForeground(new Color(71, 85, 105));
        detalleArchivo.add(archivoLabel, BorderLayout.NORTH);
        detalleArchivo.add(estadoLabel, BorderLayout.SOUTH);

        JPanel resumen = new JPanel(new GridLayout(1, 3, 12, 0));
        resumen.setOpaque(false);
        resumen.add(crearTarjetaResumen("Archivos revisados", archivosValor, AZUL));
        resumen.add(crearTarjetaResumen("Tokens encontrados", tokensValor, VERDE));
        resumen.add(crearTarjetaResumen("Errores detectados", erroresValor, ROJO));

        contenedor.add(tituloPanel, BorderLayout.NORTH);
        contenedor.add(detalleArchivo, BorderLayout.CENTER);
        contenedor.add(resumen, BorderLayout.SOUTH);
        return contenedor;
    }

    private void analizarArchivo(Path archivo) {
        archivoActual = archivo.toAbsolutePath().normalize();
        analizarButton.setEnabled(false);
        guardarReporteButton.setEnabled(false);
        estadoLabel.setForeground(new Color(71, 85, 105));
        estadoLabel.setText(validarImportsCheck.isSelected()
                ? "Analizando archivo e imports locales..."
                : "Analizando archivo...");

        final boolean validarImports = validarImportsCheck.isSelected();

        SwingWorker<List<Main.ResultadoAnalisis>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<Main.ResultadoAnalisis> doInBackground() throws Exception {
                return Main.analizarProyecto(archivoActual, validarImports);
            }

            @Override
            protected void done() {
                analizarButton.setEnabled(true);
                try {
                    mostrarResultados(get());
                } catch (Exception e) {
                    mostrarErrorLectura(e);
                }
            }
        };
        worker.execute();
    }

    // --- EL RESTO DE MÉTODOS SE MANTIENEN IGUAL (TABLAS, RENDERS, ETC) ---

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

        JLabel tituloResultados = new JLabel("Resultados del analisis");
        tituloResultados.setFont(new Font("Segoe UI", Font.BOLD, 16));
        tituloResultados.setForeground(AZUL_OSCURO);

        ampliarResultadosButton.addActionListener(e -> alternarVistaResultados());
        JPanel accionesResultados = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        accionesResultados.setBackground(Color.WHITE);
        accionesResultados.add(ampliarResultadosButton);

        barraResultados.add(tituloResultados, BorderLayout.WEST);
        barraResultados.add(accionesResultados, BorderLayout.EAST);

        panel.add(barraResultados, BorderLayout.NORTH);
        panel.add(pestanas, BorderLayout.CENTER);
        return panel;
    }

    private void alternarVistaResultados() {
        resultadosAmpliados = !resultadosAmpliados;
        if (resultadosAmpliados) {
            estadoVentanaNormal = getExtendedState();
            encabezadoPanel.setVisible(false);
            ampliarResultadosButton.setText("Vista normal");
            setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
        } else {
            encabezadoPanel.setVisible(true);
            ampliarResultadosButton.setText("Ampliar resultados");
            setExtendedState(estadoVentanaNormal);
        }
        revalidate(); repaint();
    }

    private JPanel crearTarjetaResumen(String titulo, JLabel valor, Color color) {
        JPanel tarjeta = new JPanel(new BorderLayout(0, 6));
        tarjeta.setBackground(Color.WHITE);
        tarjeta.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE),
                new EmptyBorder(14, 16, 14, 16)));
        JLabel tituloLabel = new JLabel(titulo.toUpperCase());
        tituloLabel.setForeground(new Color(100, 116, 139));
        tituloLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        valor.setForeground(color);
        tarjeta.add(tituloLabel, BorderLayout.NORTH);
        tarjeta.add(valor, BorderLayout.CENTER);
        return tarjeta;
    }

    private static JLabel crearValorResumen(String texto) {
        JLabel label = new JLabel(texto);
        label.setFont(new Font("Segoe UI", Font.BOLD, 28));
        return label;
    }

    private JButton crearBotonPrincipal(String texto) {
        JButton boton = new JButton(texto);
        boton.setBackground(Color.WHITE);
        boton.setForeground(AZUL_OSCURO);
        boton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        boton.setFocusPainted(false);
        boton.setBorder(new EmptyBorder(10, 16, 10, 16));
        return boton;
    }

    private JButton crearBotonSecundario(String texto) {
        JButton boton = new JButton(texto);
        boton.setBackground(new Color(226, 232, 240));
        boton.setForeground(AZUL_OSCURO);
        boton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        boton.setFocusPainted(false);
        boton.setBorder(new EmptyBorder(10, 16, 10, 16));
        return boton;
    }

    private void elegirArchivo() {
        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Selecciona el archivo de codigo fuente");
        selector.setPreferredSize(new Dimension(900, 580));
        selector.setFileFilter(new FileNameExtensionFilter(
                "Archivos de codigo fuente (.txt, .java, .cpp)", "txt", "java", "cpp"));
        if (archivoActual != null && archivoActual.getParent() != null) {
            selector.setCurrentDirectory(archivoActual.getParent().toFile());
        }
        if (selector.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            analizarArchivo(selector.getSelectedFile().toPath());
        }
    }

    private void abrirEjemplo() {
        Path ejemplo = localizarEjemploDemo();
        if (Files.isRegularFile(ejemplo)) {
            analizarArchivo(ejemplo);
            return;
        }

        JOptionPane.showMessageDialog(this,
                "No se encontro el archivo de ejemplo en:\n" + ejemplo,
                "Ejemplo no disponible",
                JOptionPane.WARNING_MESSAGE);
    }

    private Path localizarEjemploDemo() {
        Path[] candidatos = {
                Paths.get("untitled", "ejemplos", "programa_demo.txt"),
                Paths.get("ejemplos", "programa_demo.txt")
        };

        for (Path candidato : candidatos) {
            Path normalizado = candidato.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalizado)) return normalizado;
        }
        return candidatos[0].toAbsolutePath().normalize();
    }

    private void guardarReporte() {
        if (ultimosResultados.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Primero realiza un analisis.");
            return;
        }

        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Guardar reporte del analisis");
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

    private void mostrarResultados(List<Main.ResultadoAnalisis> resultados) {
        pestanas.removeAll();
        ultimosResultados = new ArrayList<>(resultados);
        guardarReporteButton.setEnabled(!ultimosResultados.isEmpty());
        int totalTokens = 0, totalErrores = 0;

        for (Main.ResultadoAnalisis resultado : resultados) {
            if (resultado.abortadoPorDependencia) {
                totalErrores += resultado.erroresImport.size();
                pestanas.addTab("⚠ " + resultado.archivo.getFileName(), crearPanelAbortado(resultado));
                pestanas.setForegroundAt(pestanas.getTabCount() - 1, ROJO);
                continue;
            }
            List<Token> tokensVisibles = tokensSinEOF(resultado.lexico.getTokens());
            totalTokens += tokensVisibles.size();
            totalErrores += resultado.erroresImport.size() + resultado.lexico.getErrors().size();
            if (resultado.sintactico != null) totalErrores += resultado.sintactico.getErrores().size();
            if (resultado.semantico != null) totalErrores += resultado.semantico.getErrores().size();
            pestanas.addTab(resultado.archivo.getFileName().toString(), crearPanelArchivo(resultado, tokensVisibles));
        }

        archivosValor.setText(String.valueOf(resultados.size()));
        tokensValor.setText(String.valueOf(totalTokens));
        erroresValor.setText(String.valueOf(totalErrores));
        archivoLabel.setText("Archivo principal: " + archivoActual.getFileName());

        if (totalErrores == 0) {
            estadoLabel.setForeground(VERDE);
            estadoLabel.setText("Analisis completado sin errores.");
        } else {
            estadoLabel.setForeground(ROJO);
            estadoLabel.setText("Analisis completado con " + totalErrores + " error(es).");
        }
    }

    private JComponent crearPanelAbortado(Main.ResultadoAnalisis resultado) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        JLabel icono = new JLabel("⚠ ARCHIVO RECHAZADO");
        icono.setFont(new Font("Segoe UI", Font.BOLD, 15));
        icono.setForeground(ROJO);
        JTextArea desc = new JTextArea("Este archivo no fue analizado debido a que sus dependencias son invalidas o no se cumple la estructura minima.");
        desc.setEditable(false); desc.setWrapStyleWord(true); desc.setLineWrap(true);
        panel.add(icono, BorderLayout.NORTH);
        panel.add(desc, BorderLayout.CENTER);
        return panel;
    }

    private JComponent crearPanelArchivo(Main.ResultadoAnalisis resultado, List<Token> tokensVisibles) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        List<ErrorFila> errores = crearErrores(resultado);
        JTable tablaTokens = crearTablaTokens(tokensVisibles);
        JTable tablaErrores = crearTablaErrores(errores);

        JTabbedPane vistasArchivo = new JTabbedPane();
        vistasArchivo.addTab("Tokens", crearScrollConTitulo("Lista de tokens", tablaTokens));
        vistasArchivo.addTab("Errores (" + errores.size() + ")", crearPanelErrores(tablaErrores, errores.size()));
        vistasArchivo.addTab("Arbol sintactico", crearPanelArbol(resultado, false));
        vistasArchivo.addTab("Arbol semantico", crearPanelArbol(resultado, true));
        vistasArchivo.addTab("Tabla simbolos", crearPanelTablaSimbolos(resultado));
        vistasArchivo.addTab("Gramatica", crearPanelGramatica());
        vistasArchivo.addTab("Codigo", crearPanelCodigo(resultado.source, errores));
        vistasArchivo.addTab("Reporte", crearPanelReporte(resultado, errores));

        panel.add(vistasArchivo, BorderLayout.CENTER);
        return panel;
    }

    private JComponent crearPanelArbol(Main.ResultadoAnalisis resultado, boolean semantico) {
        if (resultado.sintactico == null || resultado.sintactico.getArbol() == null) {
            return new JLabel(" El analisis sintactico se omite si existen errores lexicos.");
        }

        JTree arbol = new JTree(crearNodoSwing(resultado.sintactico.getArbol(), semantico));
        arbol.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        for (int i = 0; i < arbol.getRowCount(); i++) {
            arbol.expandRow(i);
        }
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        String aviso = resultado.sintactico.hasErrors()
                ? "Vista parcial: los fragmentos con sintaxis incompleta se omiten en semántica."
                : "Las acciones aparecen como hijos de cada nodo.";
        panel.add(new JLabel(aviso), BorderLayout.NORTH);
        panel.add(new JScrollPane(arbol), BorderLayout.CENTER);
        return panel;
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
        reporte.setBackground(new Color(248, 250, 252));
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
            sb.append("ARBOL SINTACTICO CON ACCIONES").append(System.lineSeparator());
            sb.append(resultado.sintactico.getArbol().imprimirConAcciones()).append(System.lineSeparator());
            if (resultado.semantico != null) {
                sb.append("ARBOL SEMANTICO").append(System.lineSeparator());
                sb.append(resultado.sintactico.getArbol().imprimirSemantico()).append(System.lineSeparator());
            }

            if (!resultado.tablaSimbolos().isEmpty()) {
                sb.append("TABLA DE SIMBOLOS").append(System.lineSeparator());
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
            return new JLabel(" No hay tabla de simbolos disponible.");
        }

        JTable tabla = new JTable(new SimboloTableModel(resultado.tablaSimbolos()));
        configurarTablaBase(tabla);
        ajustarColumnas(tabla, 220, 120, 130, 80, 80);
        return crearScrollConTitulo("Simbolos semanticos y ambitos persistentes", tabla);
    }

    private JComponent crearPanelGramatica() {
        Gramatica gramatica = new Gramatica();
        JTable tabla = new JTable(new GramaticaTableModel(gramatica.construirTablaGramatical()));
        configurarTablaBase(tabla);
        ajustarColumnas(tabla, 240, 80, 420);

        JTextArea producciones = new JTextArea(String.join(System.lineSeparator(), gramatica.getProducciones()));
        producciones.setEditable(false);
        producciones.setFont(new Font("Consolas", Font.PLAIN, 13));
        producciones.setBackground(new Color(248, 250, 252));
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
                if (lineasError.contains(i + 1)) StyleConstants.setBackground(estilo, new Color(254, 226, 226));
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
        pestanas.removeAll();
        ultimosResultados.clear();
        guardarReporteButton.setEnabled(false);
        pestanas.addTab("Inicio", new JLabel(" Abre un archivo para comenzar."));
    }

    private void mostrarErrorLectura(Exception e) {
        JOptionPane.showMessageDialog(this, "Error al analizar: " + e.getMessage());
    }

    private List<Token> tokensSinEOF(List<Token> tokens) {
        List<Token> visibles = new ArrayList<>();
        for (Token t : tokens) if (t.tipo != tipoToken.EOF) visibles.add(t);
        return visibles;
    }

    private List<ErrorFila> crearErrores(Main.ResultadoAnalisis resultado) {
        List<ErrorFila> errores = new ArrayList<>();
        for (String ei : resultado.erroresImport) errores.add(new ErrorFila("IMPORT", "-", "-", ei, "-", -1));
        for (compiladorErrores e : resultado.diagnosticos()) {
            errores.add(new ErrorFila(e.TIPOERROR.toString(), "L:" + e.linea + " C:" + e.columna,
                    e.lexema, e.mensaje, e.pista, e.linea));
        }
        return errores;
    }

    private Set<Integer> obtenerLineasConError(List<ErrorFila> errores) {
        Set<Integer> lineas = new LinkedHashSet<>();
        for (ErrorFila e : errores) if (e.linea > 0) lineas.add(e.linea);
        return lineas;
    }

    // --- CLASES INTERNAS DE SOPORTE ---
    private static class GradientPanel extends JPanel {
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, AZUL_OSCURO, getWidth(), getHeight(), AZUL));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
            g2.dispose();
        }
    }

    private static class TokenTableModel extends AbstractTableModel {
        private final List<Token> tokens;
        TokenTableModel(List<Token> tokens) { this.tokens = tokens; }
        @Override public int getRowCount() { return tokens.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int c) {
            switch(c) {
                case 0: return "#"; case 1: return "Tipo"; case 2: return "Lexema";
                case 3: return "Linea"; case 4: return "Columna"; default: return "";
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
                case 0: return "Tipo"; case 1: return "Ubicacion"; case 2: return "Lexema";
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
                case 3: return "Linea"; case 4: return "Columna"; default: return "";
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
                case 0: return "Simbolo"; case 1: return "Conjunto"; case 2: return "Descripcion";
                default: return "";
            }
        }
        @Override public Object getValueAt(int r, int c) {
            return filas.get(r)[c];
        }
    }

    private static class ErrorFila {
        String tipo, ubicacion, lexema, mensaje, sugerencia; int linea;
        ErrorFila(String t, String u, String lx, String m, String s, int l) {
            tipo=t; ubicacion=u; lexema=lx; mensaje=m; sugerencia=s; linea=l;
        }
    }

    private static class TokenRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            Component cp = super.getTableCellRendererComponent(t, v, s, f, r, c);
            if (!s) cp.setBackground(new Color(248, 250, 252));
            return cp;
        }
    }

    private static class ErrorRenderer extends JTextArea implements TableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            setText(v == null ? "" : v.toString());
            setLineWrap(true); setWrapStyleWord(true);
            setBackground(s ? t.getSelectionBackground() : new Color(254, 242, 242));
            return this;
        }
    }
}
