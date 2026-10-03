import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.tree.*;

/** Explorador del AST: las anotaciones son detalles, nunca aristas ficticias. */
final class VisorAst extends JPanel {
    private static final Color FONDO = new Color(248, 250, 252);
    private static final Color TEXTO = new Color(30, 41, 59);
    private static final Color AZUL = new Color(37, 99, 235);
    private static final Color INCOMPLETA = new Color(220, 38, 38);
    private static final Color INALCANZABLE = new Color(180, 83, 9);
    private static final Color ERROR_SEMANTICO = new Color(190, 24, 93);
    private static final int ANCHO = 190, ALTO = 86, SEPARACION = 24, NIVEL = 132;
    private final boolean semantico;
    private final List<Entrada> entradas = new ArrayList<>();
    private final List<Entrada> coincidencias = new ArrayList<>();
    private final Entrada raiz;
    private Entrada foco;
    private Entrada seleccion;
    private int indiceBusqueda = -1;
    private final Lienzo lienzo = new Lienzo();
    private final JScrollPane scroll = new JScrollPane(lienzo);
    private final JTree indice;
    private final JTextArea detalle = area(false);
    private final JTextArea codigo = area(false);
    private final JTextField buscar = new JTextField(16);
    private final JLabel resultadoBusqueda = new JLabel(" ");
    private final JLabel escalaLabel = new JLabel("100 %");
    private final JLabel estado = new JLabel();
    private final JButton siguiente = new JButton("Siguiente");
    private final JButton rama = new JButton("Plegar rama");
    private boolean ajustado;

    VisorAst(NodoArbolSintactico nodo, boolean semantico, String fuente, boolean parcial) {
        super(new BorderLayout(0, 8));
        this.semantico = semantico;
        setBackground(Color.WHITE);
        raiz = construir(nodo, null, 0);
        foco = raiz;
        seleccion = raiz;
        indice = new JTree(raiz.indice);
        indice.setRootVisible(true);
        indice.setShowsRootHandles(true);
        indice.setRowHeight(26);
        indice.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        indice.setBackground(Color.WHITE);
        indice.setCellRenderer(new TreeCellRenderer() {
            private final JPanel fila = new JPanel(new BorderLayout()) {
                @Override public void paint(Graphics g) { doLayout(); super.paint(g); }
            };
            private final JLabel etiqueta = new JLabel();
            { fila.add(etiqueta, BorderLayout.CENTER); fila.setOpaque(true); }
            @Override public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                Entrada e = (Entrada) ((DefaultMutableTreeNode) value).getUserObject();
                boolean diagnostico = tieneEstado(e);
                Color acento = color(e);
                fila.setToolTipText(e.nodo.getTextoVista(semantico) + (diagnostico ? " · " + estadoNodo(e) : ""));
                fila.setBackground(selected ? diagnostico ? fondoEstado(e, true) : new Color(219, 234, 254) : tree.getBackground());
                fila.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(selected ? diagnostico ? acento : AZUL : tree.getBackground()),
                        new EmptyBorder(1, 3, 1, 3)));
                fila.setForeground(selected && !diagnostico ? TEXTO : acento);
                etiqueta.setForeground(fila.getForeground());
                etiqueta.setFont(tree.getFont());
                etiqueta.setText(e.toString() + (diagnostico ? " — " + estadoNodo(e) : ""));
                etiqueta.setIcon(UIManager.getIcon(leaf ? "Tree.leafIcon" : expanded ? "Tree.openIcon" : "Tree.closedIcon"));
                if (diagnostico) {
                    etiqueta.setIcon(new Icon() {
                        public int getIconWidth() { return 16; }
                        public int getIconHeight() { return 16; }
                        public void paintIcon(Component c, Graphics g, int x, int y) {
                            g.setColor(acento); g.fillOval(x + 1, y + 1, 14, 14);
                            g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                            g.drawString("!", x + 6, y + 12);
                        }
                    });
                }
                return fila;
            }
        });
        ToolTipManager.sharedInstance().registerComponent(indice);
        indice.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode item = (DefaultMutableTreeNode) indice.getLastSelectedPathComponent();
            if (item != null) seleccionar((Entrada) item.getUserObject(), true);
        });
        indice.expandRow(0);
        JPanel navegacion = new JPanel(new BorderLayout(0, 6));
        navegacion.add(etiqueta("Estructura del programa"), BorderLayout.NORTH);
        navegacion.add(new JScrollPane(indice), BorderLayout.CENTER);
        navegacion.setMinimumSize(new Dimension(160, 120));

        codigo.setText(fuente == null ? "" : fuente);
        codigo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        detalle.setLineWrap(true);
        detalle.setWrapStyleWord(true);
        JPanel propiedades = new JPanel(new BorderLayout(0, 6));
        propiedades.add(etiqueta("Nodo seleccionado"), BorderLayout.NORTH);
        propiedades.add(new JScrollPane(detalle), BorderLayout.CENTER);
        JPanel fuentePanel = new JPanel(new BorderLayout(0, 6));
        fuentePanel.add(etiqueta("Código fuente · línea de inicio resaltada"), BorderLayout.NORTH);
        JScrollPane fuenteScroll = new JScrollPane(codigo);
        JTextArea numeros = area(false);
        numeros.setFont(codigo.getFont());
        numeros.setBackground(new Color(241, 245, 249));
        StringBuilder lineas = new StringBuilder();
        for (int i = 1; i <= codigo.getLineCount(); i++) lineas.append(i).append('\n');
        numeros.setText(lineas.toString());
        fuenteScroll.setRowHeaderView(numeros);
        fuentePanel.add(fuenteScroll, BorderLayout.CENTER);
        JSplitPane inspector = new JSplitPane(JSplitPane.VERTICAL_SPLIT, propiedades, fuentePanel) {
            private int altoAnterior;
            @Override public void doLayout() {
                if (getHeight() > 0 && getHeight() != altoAnterior) {
                    altoAnterior = getHeight();
                    setDividerLocation((int) (getHeight() * 0.58));
                }
                super.doLayout();
            }
        };
        inspector.setResizeWeight(0.58);
        inspector.setBorder(null);
        inspector.setMinimumSize(new Dimension(220, 120));
        inspector.setPreferredSize(new Dimension(330, 300));

        scroll.setBorder(BorderFactory.createLineBorder(new Color(226, 232, 240)));
        scroll.getHorizontalScrollBar().setUnitIncrement(30);
        scroll.getVerticalScrollBar().setUnitIncrement(30);
        MouseWheelListener[] desplazamiento = scroll.getMouseWheelListeners();
        for (MouseWheelListener listener : desplazamiento) scroll.removeMouseWheelListener(listener);
        scroll.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                zoom(lienzo.escala * Math.pow(1.15, -e.getPreciseWheelRotation()));
                e.consume();
            } else for (MouseWheelListener listener : desplazamiento) listener.mouseWheelMoved(e);
        });
        atajo("ENTER", () -> alternar(seleccion));
        atajo("LEFT", () -> { if (seleccion.padre != null) seleccionar(seleccion.padre, true); });
        atajo("RIGHT", () -> { if (!seleccion.hijos.isEmpty()) seleccionar(seleccion.hijos.get(0), true); });
        JPanel diagrama = new JPanel(new BorderLayout(0, 6));
        diagrama.setBackground(Color.WHITE);
        diagrama.setPreferredSize(new Dimension(700, 300));
        diagrama.add(scroll, BorderLayout.CENTER);
        diagrama.add(etiqueta("Clic: seleccionar · Doble clic o ±: plegar · Arrastrar fondo: mover · Ctrl + rueda: zoom"), BorderLayout.SOUTH);
        JSplitPane centro = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, diagrama, inspector);
        centro.setResizeWeight(1);
        centro.setDividerLocation(-1);
        centro.setBorder(null);
        JSplitPane contenido = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navegacion, centro);
        contenido.setResizeWeight(0);
        contenido.setDividerLocation(220);
        contenido.setBorder(null);
        add(crearBarra(parcial), BorderLayout.NORTH);
        add(contenido, BorderLayout.CENTER);
        estado.setBorder(new EmptyBorder(2, 6, 2, 6));
        add(estado, BorderLayout.SOUTH);
        actualizarGeometria();
        seleccionar(raiz, false);
        lienzo.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (!ajustado && scroll.getViewport().getWidth() > 50) {
                    ajustado = true;
                    zoom(0.85);
                    lienzo.scrollRectToVisible(rectanguloVista(seleccion));
                }
            }
        });
    }

    private JPanel crearBarra(boolean parcial) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        JPanel controles = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controles.setOpaque(false);
        controles.add(boton("−", () -> zoom(lienzo.escala / 1.2)));
        controles.add(escalaLabel);
        controles.add(boton("+", () -> zoom(lienzo.escala * 1.2)));
        controles.add(boton("Ajustar", this::ajustar));
        controles.add(boton("Inicio", () -> { foco = raiz; seleccionar(raiz, true); ajustar(); }));
        controles.add(boton("Enfocar rama", () -> {
            foco = seleccion;
            foco.plegado = false;
            seleccionar(foco, false);
            zoom(0.85);
            lienzo.scrollRectToVisible(rectanguloVista(seleccion));
        }));
        controles.add(boton("Expandir todo", () -> desplegarTodo(true)));
        controles.add(boton("Resumir", () -> desplegarTodo(false)));
        rama.addActionListener(e -> alternar(seleccion));
        controles.add(rama);
        JPanel busqueda = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        busqueda.setOpaque(false);
        busqueda.add(new JLabel("Buscar nodo:"));
        buscar.setToolTipText("Nombre, valor o tipo del nodo. Enter pasa a la siguiente coincidencia.");
        busqueda.add(buscar);
        siguiente.addActionListener(e -> siguienteCoincidencia());
        siguiente.setEnabled(false);
        buscar.addActionListener(e -> siguienteCoincidencia());
        buscar.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { buscarNodos(); }
            public void removeUpdate(DocumentEvent e) { buscarNodos(); }
            public void changedUpdate(DocumentEvent e) { buscarNodos(); }
        });
        busqueda.add(siguiente);
        busqueda.add(resultadoBusqueda);
        JPanel leyenda = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        leyenda.setOpaque(false);
        leyenda.add(etiqueta(parcial ? "AST parcial · Hay errores de sintaxis" : (semantico ? "AST semántico" : "AST sintáctico")));
        leyenda.add(marca("Estructura", AZUL));
        leyenda.add(marca("Expresión / valor", new Color(124, 58, 237)));
        if (semantico) {
            leyenda.add(marca("Conversión", new Color(13, 148, 136)));
            leyenda.add(marca("Inalcanzable", INALCANZABLE));
            leyenda.add(marca("Error semántico", ERROR_SEMANTICO));
        }
        leyenda.add(marca("Sintaxis incompleta", INCOMPLETA));
        JPanel arriba = new JPanel(new GridLayout(2, 1, 0, 6));
        arriba.setOpaque(false);
        arriba.add(controles);
        arriba.add(busqueda);
        panel.add(arriba, BorderLayout.CENTER);
        panel.add(leyenda, BorderLayout.SOUTH);
        return panel;
    }

    private Entrada construir(NodoArbolSintactico nodo, Entrada padre, int nivel) {
        Entrada entrada = new Entrada(nodo, padre, nivel);
        entradas.add(entrada);
        for (NodoArbolSintactico hijo : semantico ? nodo.getHijosSemanticos() : nodo.getHijos()) {
            Entrada nuevo = construir(hijo, entrada, nivel + 1);
            entrada.hijos.add(nuevo);
            entrada.indice.add(nuevo.indice);
        }
        entrada.plegado = nivel >= 3 && !entrada.hijos.isEmpty();
        return entrada;
    }

    private void seleccionar(Entrada entrada, boolean centrar) {
        seleccion = entrada;
        boolean dentro = false;
        for (Entrada p = entrada; p != null; p = p.padre) if (p == foco) dentro = true;
        if (!dentro) foco = raiz;
        for (Entrada p = entrada.padre; p != null; p = p.padre) p.plegado = false;
        actualizarGeometria();
        TreePath path = new TreePath(entrada.indice.getPath());
        if (!path.equals(indice.getSelectionPath())) indice.setSelectionPath(path);
        indice.scrollPathToVisible(path);
        if (indice.getParent() instanceof JViewport) {
            JViewport vista = (JViewport) indice.getParent();
            Rectangle bounds = indice.getPathBounds(path);
            if (bounds != null) vista.setViewPosition(new Point(Math.max(0, bounds.x - 12), vista.getViewPosition().y));
        }
        detalle.setText(describir(entrada));
        detalle.setCaretPosition(0);
        codigo.getHighlighter().removeAllHighlights();
        NodoArbolSintactico n = entrada.nodo;
        if (n.getLinea() > 0) {
            try {
                int inicio = codigo.getLineStartOffset(n.getLinea() - 1);
                int fin = codigo.getLineEndOffset(n.getLinea() - 1);
                codigo.getHighlighter().addHighlight(inicio, fin,
                        new DefaultHighlighter.DefaultHighlightPainter(new Color(219, 234, 254)));
                codigo.setCaretPosition(Math.min(fin, inicio + Math.max(0, n.getColumna() - 1)));
            } catch (javax.swing.text.BadLocationException ignored) { /* Nodo sin ubicación disponible. */ }
        }
        rama.setEnabled(!entrada.hijos.isEmpty());
        rama.setText(entrada.plegado ? "Expandir rama" : "Plegar rama");
        if (centrar) SwingUtilities.invokeLater(() -> lienzo.scrollRectToVisible(rectanguloVista(entrada)));
        lienzo.repaint();
    }

    private String describir(Entrada entrada) {
        NodoArbolSintactico n = entrada.nodo;
        StringBuilder sb = new StringBuilder(titulo(n)).append('\n');
        if (tieneEstado(entrada)) sb.append("\nEstado: ").append(estadoNodo(entrada)).append('\n');
        if (!n.esSintaxisIncompleta() && origenIncompleto(entrada) != null) {
            sb.append("\nPertenece a un fragmento con sintaxis incompleta: ")
                    .append(titulo(origenIncompleto(entrada).nodo)).append(". El marcador corresponde al fragmento contenedor.\n");
        }
        if (semantico && n.tieneErrorSemantico()) {
            sb.append("\nErrores semánticos del nodo\n");
            for (String error : n.getErroresSemanticos()) sb.append("• ").append(error).append('\n');
        }
        sb.append("\nNombre AST: ").append(n.getNombre());
        if (n.getValor() != null && !n.getValor().isEmpty()) sb.append("\nValor: ").append(n.getValor());
        sb.append("\nUbicación: ").append(n.getLinea() > 0 ? "línea " + n.getLinea() + ", columna " + n.getColumna() : "sin ubicación propia");
        sb.append("\nHijos directos: ").append(entrada.hijos.size());
        List<String> ruta = new ArrayList<>();
        for (Entrada p = entrada; p != null; p = p.padre) ruta.add(0, titulo(p.nodo));
        sb.append("\n\nRuta\n").append(String.join(" › ", ruta));
        if (esResumenParcial(entrada)) sb.append("\n\nAST parcial: el programa contiene fragmentos con sintaxis incompleta. Consulta los nodos marcados.");
        else if (n.esSintaxisIncompleta()) sb.append("\n\nSintaxis incompleta: este fragmento no se valida semánticamente.");
        if (semantico) {
            sb.append("\n\nAnotaciones semánticas");
            sb.append("\nTipo: ").append(n.getTipoSemantico() == null ? "sin tipo anotado" : n.getTipoSemantico());
            if (n.getTipoOperacion() != null) sb.append("\nTipo de operación: ").append(n.getTipoOperacion());
            if (n.getTipoReceptor() != null) sb.append("\nTipo de receptor: ").append(n.getTipoReceptor());
            if (n.getSimboloResuelto() != null) {
                Semantico.Simbolo s = n.getSimboloResuelto();
                sb.append("\nSímbolo: ").append(s.nombre).append(" · ").append(s.rol).append(" · ").append(s.tipo);
                if (s.linea > 0) sb.append("\nDeclarado en línea ").append(s.linea).append(", columna ").append(s.columna);
            }
            if (n.getAmbitoResuelto() != null) sb.append("\nÁmbito: ").append(n.getAmbitoResuelto().tipo);
            if (n.getDespacho() != NodoArbolSintactico.Despacho.NINGUNO) sb.append("\nDespacho: ").append(n.getDespacho());
            if (n.esInalcanzable()) sb.append("\nCódigo inalcanzable");
        }
        if (!n.getAccionesSemanticas().isEmpty()) {
            sb.append("\n\nAcciones semánticas");
            for (String accion : n.getAccionesSemanticas()) sb.append("\n• ").append(accion);
        }
        return sb.toString();
    }

    private void alternar(Entrada e) {
        if (e.hijos.isEmpty()) return;
        e.plegado = !e.plegado;
        seleccionar(e, false);
    }

    private void atajo(String tecla, Runnable accion) {
        lienzo.getInputMap().put(KeyStroke.getKeyStroke(tecla), tecla);
        lienzo.getActionMap().put(tecla, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { accion.run(); }
        });
    }

    private void desplegarTodo(boolean expandir) {
        for (Entrada e : entradas) e.plegado = !expandir && e.nivel >= 2 && !e.hijos.isEmpty();
        seleccionar(expandir ? seleccion : raiz, false);
        ajustar();
    }

    private void buscarNodos() {
        coincidencias.clear();
        indiceBusqueda = -1;
        String consulta = buscar.getText().trim().toLowerCase(Locale.ROOT);
        if (!consulta.isEmpty()) {
            for (Entrada e : entradas) {
                if (e.nodo.getTextoVista(semantico).toLowerCase(Locale.ROOT).contains(consulta)
                        || estadoNodo(e).toLowerCase(Locale.ROOT).contains(consulta)
                        || titulo(e.nodo).toLowerCase(Locale.ROOT).contains(consulta)) coincidencias.add(e);
            }
        }
        siguiente.setEnabled(!coincidencias.isEmpty());
        resultadoBusqueda.setText(consulta.isEmpty() ? " " : coincidencias.size() + " coincidencias");
        lienzo.repaint();
    }

    private void siguienteCoincidencia() {
        if (coincidencias.isEmpty()) return;
        indiceBusqueda = (indiceBusqueda + 1) % coincidencias.size();
        seleccionar(coincidencias.get(indiceBusqueda), true);
        resultadoBusqueda.setText((indiceBusqueda + 1) + " de " + coincidencias.size());
    }

    private void actualizarGeometria() {
        for (Entrada e : entradas) e.visible = false;
        medir(foco);
        ubicar(foco, 36);
        double alto = 0;
        int visibles = 0;
        for (Entrada e : entradas) if (e.visible) { visibles++; alto = Math.max(alto, e.y + ALTO + 36); }
        lienzo.ancho = foco.ancho + 72;
        lienzo.alto = alto;
        lienzo.setPreferredSize(new Dimension((int) Math.ceil(lienzo.ancho * lienzo.escala), (int) Math.ceil(alto * lienzo.escala)));
        lienzo.revalidate();
        estado.setText(visibles + " de " + entradas.size() + " nodos visibles · "
                + (foco != raiz ? "Rama: " + titulo(foco.nodo) + " · Inicio vuelve al árbol completo" : (semantico ? "Aristas semánticas con conversiones implícitas" : "Aristas del árbol sintáctico"))
                + " · Las acciones se consultan en los detalles");
        lienzo.repaint();
    }

    private double medir(Entrada e) {
        double ancho = 0;
        if (!e.plegado) for (Entrada h : e.hijos) ancho += medir(h) + SEPARACION;
        e.ancho = Math.max(ANCHO, ancho > 0 ? ancho - SEPARACION : 0);
        return e.ancho;
    }

    private void ubicar(Entrada e, double izquierda) {
        e.visible = true;
        e.x = izquierda + (e.ancho - ANCHO) / 2;
        e.y = 30 + (e.nivel - foco.nivel) * NIVEL;
        if (!e.plegado) {
            double total = 0;
            for (Entrada h : e.hijos) total += h.ancho + SEPARACION;
            double x = izquierda + (e.ancho - Math.max(0, total - SEPARACION)) / 2;
            for (Entrada h : e.hijos) { ubicar(h, x); x += h.ancho + SEPARACION; }
        }
    }

    private void ajustar() {
        Dimension d = scroll.getViewport().getExtentSize();
        if (d.width < 50 || d.height < 50) return;
        zoom(Math.min(1, Math.min((d.width - 20) / lienzo.ancho, (d.height - 20) / lienzo.alto)));
        scroll.getViewport().setViewPosition(new Point(0, 0));
    }

    private void zoom(double escala) {
        JViewport vista = scroll.getViewport();
        Point p = vista.getViewPosition();
        Dimension d = vista.getExtentSize();
        double cx = (p.x + d.width / 2.0) / lienzo.escala;
        double cy = (p.y + d.height / 2.0) / lienzo.escala;
        lienzo.escala = Math.max(0.15, Math.min(2.5, escala));
        escalaLabel.setText(Math.round(lienzo.escala * 100) + " %");
        actualizarGeometria();
        lienzo.setSize(lienzo.getPreferredSize());
        vista.setViewPosition(new Point(Math.max(0, Math.min((int) (cx * lienzo.escala - d.width / 2), lienzo.getWidth() - d.width)),
                Math.max(0, Math.min((int) (cy * lienzo.escala - d.height / 2), lienzo.getHeight() - d.height))));
    }

    private Rectangle rectanguloVista(Entrada e) {
        return new Rectangle((int) ((e.x - 20) * lienzo.escala), (int) ((e.y - 20) * lienzo.escala),
                (int) ((ANCHO + 40) * lienzo.escala), (int) ((ALTO + 40) * lienzo.escala));
    }

    private Entrada origenIncompleto(Entrada entrada) {
        for (Entrada e = entrada; e != null; e = e.padre) {
            if (e.nodo.esSintaxisIncompleta() && (e == entrada || !esResumenParcial(e))) return e;
        }
        return null;
    }
    private boolean esResumenParcial(Entrada e) {
        return e.padre == null && e.nodo.getNombre().equals("PROGRAMA") && e.nodo.esSintaxisIncompleta();
    }
    private boolean tieneEstado(Entrada e) {
        return origenIncompleto(e) != null || semantico && (e.nodo.esInalcanzable() || e.nodo.tieneErrorSemantico());
    }
    private String estadoNodo(Entrada e) {
        if (e.nodo.esSintaxisIncompleta()) return esResumenParcial(e) ? "AST parcial" : "Sintaxis incompleta";
        if (semantico && e.nodo.esInalcanzable()) return "Inalcanzable";
        if (semantico && e.nodo.tieneErrorSemantico()) return "Error semántico";
        if (origenIncompleto(e) != null) return "Fragmento incompleto";
        return "";
    }
    private Color fondoEstado(Entrada e, boolean seleccionado) {
        Color acento = color(e);
        double intensidad = seleccionado ? 0.16 : 0.08;
        return new Color((int) (255 * (1 - intensidad) + acento.getRed() * intensidad),
                (int) (255 * (1 - intensidad) + acento.getGreen() * intensidad),
                (int) (255 * (1 - intensidad) + acento.getBlue() * intensidad));
    }
    private Color color(Entrada e) {
        NodoArbolSintactico n = e.nodo;
        if (n.esSintaxisIncompleta()) return INCOMPLETA;
        if (semantico && n.esInalcanzable()) return INALCANZABLE;
        if (semantico && n.tieneErrorSemantico()) return ERROR_SEMANTICO;
        if (origenIncompleto(e) != null) return INCOMPLETA;
        if (semantico && n.getNombre().equals("CONVERSION_IMPLICITA")) return new Color(13, 148, 136);
        if (n.getNombre().matches(".*(EXPRESION|UNARIO|LITERAL|IDENTIFICADOR|ENTERO|FLOAT|STRING|DECIMAL|CADENA|BOOLEANO).*")) return new Color(124, 58, 237);
        return AZUL;
    }

    private static String titulo(NodoArbolSintactico n) {
        String s = n.getNombre().replace('_', ' ').toLowerCase(Locale.ROOT);
        s = s.replace("conversion", "conversión").replace("implicita", "implícita")
                .replace("expresion", "expresión").replace("declaracion", "declaración")
                .replace("asignacion", "asignación").replace("inicializacion", "inicialización")
                .replace("parametros", "parámetros");
        return s.isEmpty() ? "Nodo" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static JTextArea area(boolean editable) {
        JTextArea area = new JTextArea();
        area.setEditable(editable);
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        area.setForeground(TEXTO);
        area.setBorder(new EmptyBorder(12, 12, 12, 12));
        return area;
    }
    private static JLabel etiqueta(String texto) {
        JLabel label = new JLabel(texto);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        label.setForeground(new Color(71, 85, 105));
        return label;
    }
    private static JLabel marca(String texto, Color color) {
        JLabel label = etiqueta("● " + texto);
        label.setForeground(color);
        return label;
    }
    private static JButton boton(String texto, Runnable accion) {
        JButton b = new JButton(texto);
        b.addActionListener(e -> accion.run());
        return b;
    }

    private static final class Entrada {
        final NodoArbolSintactico nodo;
        final Entrada padre;
        final int nivel;
        final List<Entrada> hijos = new ArrayList<>();
        final DefaultMutableTreeNode indice;
        boolean plegado, visible;
        double x, y, ancho;
        Entrada(NodoArbolSintactico nodo, Entrada padre, int nivel) {
            this.nodo = nodo; this.padre = padre; this.nivel = nivel;
            indice = new DefaultMutableTreeNode(this);
        }
        @Override public String toString() {
            return titulo(nodo) + (nodo.getValor() == null ? "" : ": " + nodo.getValor());
        }
    }

    private final class Lienzo extends JComponent {
        double escala = 1, ancho, alto;
        Lienzo() {
            setOpaque(true);
            setBackground(FONDO);
            setFocusable(true);
            setToolTipText("");
            MouseAdapter raton = new MouseAdapter() {
                Point inicio, vistaInicial;
                boolean arrastrando;
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    Entrada entrada = localizar(e.getPoint());
                    if (entrada == null || SwingUtilities.isMiddleMouseButton(e)) {
                        inicio = SwingUtilities.convertPoint(Lienzo.this, e.getPoint(), scroll.getViewport());
                        vistaInicial = scroll.getViewport().getViewPosition();
                        arrastrando = true;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        seleccionar(entrada, false);
                        double x = e.getX() / escala, y = e.getY() / escala;
                        boolean controlRama = x > entrada.x + ANCHO - 30 && y > entrada.y + ALTO - 28;
                        if (e.getClickCount() == 2 && !controlRama || e.getClickCount() == 1 && controlRama) alternar(entrada);
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (!arrastrando) return;
                    Point actual = SwingUtilities.convertPoint(Lienzo.this, e.getPoint(), scroll.getViewport());
                    Dimension d = scroll.getViewport().getExtentSize();
                    scroll.getViewport().setViewPosition(new Point(
                            Math.max(0, Math.min(getWidth() - d.width, vistaInicial.x + inicio.x - actual.x)),
                            Math.max(0, Math.min(getHeight() - d.height, vistaInicial.y + inicio.y - actual.y))));
                }
                @Override public void mouseReleased(MouseEvent e) { arrastrando = false; setCursor(Cursor.getDefaultCursor()); }
            };
            addMouseListener(raton);
            addMouseMotionListener(raton);
        }
        private Entrada localizar(Point p) {
            for (Entrada e : entradas) if (e.visible && new Rectangle2D.Double(e.x, e.y, ANCHO, ALTO).contains(p.x / escala, p.y / escala)) return e;
            return null;
        }
        @Override public String getToolTipText(MouseEvent evento) {
            Entrada e = localizar(evento.getPoint());
            return e == null ? null : e.nodo.getTextoVista(semantico) + (tieneEstado(e) ? " · " + estadoNodo(e) : "");
        }
        @Override protected void paintComponent(Graphics original) {
            super.paintComponent(original);
            Graphics2D g = (Graphics2D) original.create();
            g.setColor(FONDO);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.scale(escala, escala);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(148, 163, 184));
            g.setStroke(new BasicStroke(1.5f));
            for (Entrada e : entradas) if (e.visible && e.padre != null && e.padre.visible) {
                Entrada p = e.padre;
                double x1 = p.x + ANCHO / 2.0, y1 = p.y + ALTO, x2 = e.x + ANCHO / 2.0, y2 = e.y;
                Path2D linea = new Path2D.Double();
                linea.moveTo(x1, y1);
                linea.curveTo(x1, y1 + 24, x2, y2 - 24, x2, y2);
                g.draw(linea);
            }
            for (Entrada e : entradas) if (e.visible) pintarNodo(g, e);
            g.dispose();
        }
        private void pintarNodo(Graphics2D g, Entrada e) {
            Color acento = color(e);
            boolean diagnostico = tieneEstado(e);
            RoundRectangle2D caja = new RoundRectangle2D.Double(e.x, e.y, ANCHO, ALTO, 14, 14);
            if (e == seleccion && diagnostico) {
                g.setColor(AZUL); g.setStroke(new BasicStroke(1.5f));
                g.draw(new RoundRectangle2D.Double(e.x - 4, e.y - 4, ANCHO + 8, ALTO + 8, 18, 18));
            }
            g.setColor(diagnostico ? fondoEstado(e, e == seleccion) : e == seleccion ? new Color(239, 246, 255) : Color.WHITE);
            g.fill(caja);
            g.setColor(diagnostico ? acento : e == seleccion ? AZUL : coincidencias.contains(e) ? new Color(217, 119, 6) : new Color(203, 213, 225));
            g.setStroke(new BasicStroke(e == seleccion || coincidencias.contains(e) ? 2.5f : diagnostico ? 1.8f : 1));
            g.draw(caja);
            g.setColor(acento);
            g.fillRoundRect((int) e.x + 10, (int) e.y + 12, 4, 22, 4, 4);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(diagnostico ? acento : TEXTO);
            texto(g, titulo(e.nodo), e.x + 22, e.y + 26, ANCHO - 32);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            g.setColor(TEXTO);
            texto(g, e.nodo.getValor() == null ? "" : e.nodo.getValor(), e.x + 12, e.y + 46, ANCHO - 24);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(acento);
            String info = diagnostico ? estadoNodo(e)
                    : semantico && e.nodo.getTipoSemantico() != null ? "Tipo: " + e.nodo.getTipoSemantico()
                    : e.nodo.getLinea() > 0 ? "Línea " + e.nodo.getLinea() : "Nodo estructural";
            texto(g, info, e.x + 12, e.y + 70, ANCHO - 48);
            if (!e.hijos.isEmpty()) {
                g.setColor(acento);
                g.fillRoundRect((int) e.x + ANCHO - 27, (int) e.y + ALTO - 25, 20, 18, 6, 6);
                g.setColor(Color.WHITE);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                g.drawString(e.plegado ? "+" : "−", (int) e.x + ANCHO - 23, (int) e.y + ALTO - 11);
            }
        }
        private void texto(Graphics2D g, String s, double x, double y, int maximo) {
            String visible = s.replace('\n', ' ').replace('\r', ' ');
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(visible) > maximo) {
                while (!visible.isEmpty() && fm.stringWidth(visible + "…") > maximo) visible = visible.substring(0, visible.length() - 1);
                visible += "…";
            }
            g.drawString(visible, (float) x, (float) y);
        }
    }
}
