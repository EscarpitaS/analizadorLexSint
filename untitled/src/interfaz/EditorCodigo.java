import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;

/** Editor de una unidad; el resaltado tolera fragmentos aún incompletos. */
final class EditorCodigo extends JPanel {
    static final Color NORMAL = new Color(30, 41, 59);
    static final Color CLAVE = new Color(124, 58, 237);
    static final Color TIPO = new Color(3, 105, 161);
    static final Color CADENA = new Color(21, 128, 61);
    static final Color NUMERO = new Color(180, 83, 9);
    static final Color COMENTARIO = new Color(100, 116, 139);
    private final JTextPane texto = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() == null || getUI().getPreferredSize(this).width <= getParent().getWidth();
        }
    };
    private final UndoManager historial = new UndoManager();
    private CompoundEdit grupo;
    private final JLabel nombre = new JLabel("Sin título");
    private final JLabel posicion = new JLabel();
    private final JPanel barra = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JPanel superior = new JPanel(new BorderLayout(0, 6));
    private final JPanel pie = new JPanel(new BorderLayout(12, 0));
    private final JButton deshacer = new JButton("Deshacer");
    private final JButton rehacer = new JButton("Rehacer");
    private final JButton nuevo = new JButton("Nuevo");
    private final JButton guardar = new JButton("Guardar");
    private final JButton guardarComo = new JButton("Guardar como…");
    private final NumerosLineas numeros = new NumerosLineas();
    private final List<Runnable> cambios = new ArrayList<>();
    private final Timer espera = new Timer(140, e -> iniciarResaltado());
    private SwingWorker<List<Tramo>, Void> trabajador;
    private Path archivo;
    private String guardado = "";
    private long revision;
    private boolean cargando;
    private Object lineaActual;
    private final CompletarEditor completar;

    EditorCodigo() {
        super(new BorderLayout(0, 8));
        setBackground(Color.WHITE);
        texto.setEditorKit(new StyledEditorKit() {
            @Override public ViewFactory getViewFactory() {
                return elemento -> {
                    String tipo = elemento.getName();
                    if (AbstractDocument.ParagraphElementName.equals(tipo)) return new ParagraphView(elemento) {
                        @Override protected void layout(int ancho, int alto) { super.layout(Integer.MAX_VALUE / 2, alto); }
                        @Override public float getMinimumSpan(int eje) { return getPreferredSpan(eje); }
                    };
                    if (AbstractDocument.SectionElementName.equals(tipo)) return new BoxView(elemento, View.Y_AXIS);
                    if (StyleConstants.ComponentElementName.equals(tipo)) return new ComponentView(elemento);
                    if (StyleConstants.IconElementName.equals(tipo)) return new IconView(elemento);
                    return new LabelView(elemento);
                };
            }
        });
        texto.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        texto.setForeground(NORMAL);
        texto.setCaretColor(new Color(37, 99, 235));
        texto.setSelectionColor(new Color(191, 219, 254));
        texto.setBorder(new EmptyBorder(12, 12, 12, 12));
        texto.setFocusTraversalKeysEnabled(false);
        historial.setLimit(500);
        espera.setRepeats(false);
        ((AbstractDocument) texto.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int offset, String s, AttributeSet attrs) throws BadLocationException {
                fb.insertString(offset, normalizar(s), attrs);
            }
            @Override public void replace(FilterBypass fb, int offset, int length, String s, AttributeSet attrs) throws BadLocationException {
                iniciarGrupo();
                try { fb.replace(offset, length, normalizar(s), attrs); }
                finally { terminarGrupo(); }
            }
        });
        texto.getDocument().addUndoableEditListener(e -> {
            if (cargando || e.getEdit() instanceof DocumentEvent
                    && ((DocumentEvent) e.getEdit()).getType() == DocumentEvent.EventType.CHANGE) return;
            if (grupo != null) grupo.addEdit(e.getEdit()); else historial.addEdit(e.getEdit());
            actualizarBotones();
        });
        texto.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { modificado(); }
            public void removeUpdate(DocumentEvent e) { modificado(); }
            public void changedUpdate(DocumentEvent e) { /* Los colores no alteran el contenido. */ }
        });
        texto.addCaretListener(e -> actualizarPosicion());
        texto.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { numeros.repaint(); }
        });
        completar = new CompletarEditor(texto, this::getRevision);
        JScrollPane scroll = new JScrollPane(texto);
        scroll.getViewport().setBackground(Color.WHITE);
        scroll.setRowHeaderView(numeros);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        barra.setOpaque(false);
        for (JButton b : new JButton[] { nuevo, guardar, guardarComo, deshacer, rehacer }) barra.add(b);
        JButton completarBoton = new JButton("Completar");
        completarBoton.setToolTipText("Palabras reservadas y símbolos del ámbito actual (Ctrl+Espacio)");
        completarBoton.addActionListener(e -> { texto.requestFocusInWindow(); completar.solicitar(true); });
        barra.add(completarBoton);
        nombre.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        superior.setOpaque(false);
        superior.add(barra, BorderLayout.NORTH);
        superior.add(nombre, BorderLayout.SOUTH);
        pie.setOpaque(false);
        pie.add(posicion, BorderLayout.EAST);
        add(superior, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(pie, BorderLayout.SOUTH);
        deshacer.addActionListener(e -> deshacer());
        rehacer.addActionListener(e -> rehacer());
        atajo("control Z", this::deshacer);
        atajo("control Y", this::rehacer);
        atajo("control shift Z", this::rehacer);
        atajo("TAB", () -> { if (!completar.aceptar()) sangrar(false); });
        atajo("shift TAB", () -> sangrar(true));
        atajo("ENTER", () -> { if (!completar.aceptar()) nuevaLinea(); });
        actualizarPosicion();
        actualizarBotones();
    }

    void integrarAnalisis(JButton abrir, JButton ejemplo, JComponent analisis, JLabel estado) {
        barra.add(abrir, 1);
        barra.add(ejemplo, 2);
        JPanel controles = new JPanel(new BorderLayout(0, 6));
        controles.setOpaque(false);
        controles.add(analisis, BorderLayout.NORTH);
        controles.add(nombre, BorderLayout.SOUTH);
        superior.add(controles, BorderLayout.SOUTH);
        estado.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        estado.setForeground(Color.BLACK);
        pie.add(estado, BorderLayout.CENTER);
        EstiloInterfaz.aplicar(barra);
        EstiloInterfaz.aplicar(analisis);
        revalidate();
    }

    void configurarAcciones(Runnable nuevo, Runnable abrir, Runnable guardar, Runnable guardarComo) {
        this.nuevo.addActionListener(e -> nuevo.run());
        this.guardar.addActionListener(e -> guardar.run());
        this.guardarComo.addActionListener(e -> guardarComo.run());
        atajo("control N", nuevo);
        atajo("control O", abrir);
        atajo("control S", guardar);
        atajo("control shift S", guardarComo);
    }
    void alCambiar(Runnable accion) { cambios.add(accion); }
    Path getArchivo() { return archivo; }
    String getTexto() { return texto.getText(); }
    long getRevision() { return revision; }
    boolean tieneCambios() { return !guardado.equals(getTexto()); }
    void enfocar() { texto.requestFocusInWindow(); }
    boolean irAUbicacion(int linea, int columna) {
        Element raiz = texto.getDocument().getDefaultRootElement();
        if (linea < 1 || linea > raiz.getElementCount()) return false;
        completar.cerrar();
        Element fila = raiz.getElement(linea - 1);
        int inicio = fila.getStartOffset();
        int fin = Math.min(texto.getDocument().getLength(), fila.getEndOffset());
        if (fin > inicio && getTexto().charAt(fin - 1) == '\n') fin--;
        texto.setCaretPosition(inicio + Math.min(Math.max(0, columna - 1), fin - inicio));
        enfocar();
        SwingUtilities.invokeLater(() -> {
            try {
                Rectangle2D posicion = texto.modelToView2D(texto.getCaretPosition());
                if (posicion != null) texto.scrollRectToVisible(posicion.getBounds());
            } catch (BadLocationException ignored) { }
        });
        return true;
    }
    CompletarEditor getCompletar() { return completar; }

    void cargar(Path ruta, String contenido) {
        completar.cerrar();
        espera.stop();
        if (trabajador != null) trabajador.cancel(true);
        cargando = true;
        try {
            archivo = ruta == null ? null : ruta.toAbsolutePath().normalize();
            guardado = normalizar(contenido);
            texto.setText(guardado);
            texto.setCaretPosition(0);
            historial.discardAllEdits();
            revision++;
        } finally { cargando = false; }
        resaltarAhora();
        actualizarEstado();
        notificar();
    }

    void guardar(Path destino) throws IOException {
        Path ruta = destino.toAbsolutePath().normalize();
        String contenido = getTexto();
        Files.writeString(ruta, contenido, StandardCharsets.UTF_8);
        archivo = ruta;
        guardado = contenido;
        actualizarEstado();
        notificar();
    }

    private static String normalizar(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n');
    }
    private void modificado() {
        if (cargando) return;
        revision++;
        if (trabajador != null) trabajador.cancel(true);
        espera.restart();
        numeros.revalidate();
        numeros.repaint();
        actualizarEstado();
        notificar();
    }
    private void notificar() { for (Runnable r : cambios) r.run(); }
    private void actualizarEstado() {
        nombre.setText((tieneCambios() ? "● " : "") + (archivo == null ? "Sin título" : archivo.getFileName().toString())
                + (tieneCambios() ? " · cambios sin guardar" : ""));
        nombre.setToolTipText(archivo == null ? "Documento nuevo" : archivo.toString());
        actualizarPosicion();
        actualizarBotones();
    }
    private void actualizarBotones() {
        deshacer.setEnabled(historial.canUndo());
        rehacer.setEnabled(historial.canRedo());
    }
    private void actualizarPosicion() {
        Element raiz = texto.getDocument().getDefaultRootElement();
        int fila = raiz.getElementIndex(texto.getCaretPosition());
        posicion.setText("Ln " + (fila + 1) + ", Col " + (texto.getCaretPosition() - raiz.getElement(fila).getStartOffset() + 1) + " · UTF-8 · 4 espacios");
        if (lineaActual != null) texto.getHighlighter().removeHighlight(lineaActual);
        try {
            Element linea = raiz.getElement(fila);
            lineaActual = texto.getHighlighter().addHighlight(linea.getStartOffset(), Math.min(texto.getDocument().getLength(), linea.getEndOffset()),
                    new DefaultHighlighter.DefaultHighlightPainter(new Color(241, 245, 249)));
        } catch (BadLocationException ignored) { }
        numeros.repaint();
    }
    void deshacer() { if (historial.canUndo()) { historial.undo(); actualizarBotones(); } }
    void rehacer() { if (historial.canRedo()) { historial.redo(); actualizarBotones(); } }
    private void iniciarGrupo() { grupo = new CompoundEdit(); }
    private void terminarGrupo() {
        if (grupo != null) {
            grupo.end();
            if (!cargando && grupo.canUndo()) historial.addEdit(grupo);
            grupo = null;
            actualizarBotones();
        }
    }
    private void atajo(String tecla, Runnable accion) {
        texto.getInputMap().put(KeyStroke.getKeyStroke(tecla), tecla);
        texto.getActionMap().put(tecla, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { accion.run(); }
        });
    }
    private void nuevaLinea() {
        Element raiz = texto.getDocument().getDefaultRootElement();
        int inicio = raiz.getElement(raiz.getElementIndex(texto.getSelectionStart())).getStartOffset();
        String antes = getTexto().substring(inicio, texto.getSelectionStart());
        int blancos = 0;
        while (blancos < antes.length() && (antes.charAt(blancos) == ' ' || antes.charAt(blancos) == '\t')) blancos++;
        texto.replaceSelection("\n" + antes.substring(0, blancos) + (antes.stripTrailing().endsWith("{") ? "    " : ""));
    }
    private void sangrar(boolean quitar) {
        Element raiz = texto.getDocument().getDefaultRootElement();
        int inicioSeleccion = texto.getSelectionStart(), finSeleccion = texto.getSelectionEnd();
        Element primera = raiz.getElement(raiz.getElementIndex(inicioSeleccion));
        if (inicioSeleccion == finSeleccion && !quitar) {
            texto.replaceSelection(" ".repeat(4 - (inicioSeleccion - primera.getStartOffset()) % 4));
            return;
        }
        int inicio = primera.getStartOffset();
        int fin = Math.min(texto.getDocument().getLength(), raiz.getElement(raiz.getElementIndex(Math.max(inicioSeleccion, finSeleccion - 1))).getEndOffset());
        String fragmento = getTexto().substring(inicio, fin);
        String[] lineas = fragmento.split("\n", -1);
        StringBuilder reemplazo = new StringBuilder();
        int cambioPrimera = 0;
        for (int i = 0; i < lineas.length; i++) {
            String s = lineas[i];
            if (i == lineas.length - 1 && s.isEmpty() && fragmento.endsWith("\n")) break;
            int cambio;
            if (quitar) {
                int n = 0;
                while (n < Math.min(4, s.length()) && s.charAt(n) == ' ') n++;
                if (n == 0 && s.startsWith("\t")) n = 1;
                cambio = -n;
                reemplazo.append(s.substring(n));
            } else { cambio = 4; reemplazo.append("    ").append(s); }
            if (i == 0) cambioPrimera = cambio;
            if (i < lineas.length - 1) reemplazo.append('\n');
        }
        texto.select(inicio, fin);
        texto.replaceSelection(reemplazo.toString());
        if (inicioSeleccion == finSeleccion) texto.setCaretPosition(Math.max(inicio, inicioSeleccion + cambioPrimera));
        else texto.select(inicio, inicio + reemplazo.length());
    }

    private void iniciarResaltado() {
        String fuente = getTexto();
        long version = revision;
        trabajador = new SwingWorker<>() {
            @Override protected List<Tramo> doInBackground() { return colorear(fuente); }
            @Override protected void done() {
                if (isCancelled() || revision != version) return;
                try { aplicar(get()); } catch (Exception e) { /* El siguiente cambio reintenta el resaltado. */ }
            }
        };
        trabajador.execute();
    }
    /** Vía síncrona para carga inicial y pruebas; editar usa un trabajador en segundo plano. */
    void resaltarAhora() {
        espera.stop();
        if (trabajador != null) trabajador.cancel(true);
        aplicar(colorear(getTexto()));
    }
    private void aplicar(List<Tramo> tramos) {
        StyledDocument doc = texto.getStyledDocument();
        doc.setCharacterAttributes(0, doc.getLength(), estilo(NORMAL, false, false), true);
        for (Tramo tramo : tramos) doc.setCharacterAttributes(tramo.inicio, tramo.fin - tramo.inicio,
                estilo(tramo.color, tramo.negrita, tramo.cursiva), true);
    }
    private static SimpleAttributeSet estilo(Color color, boolean negrita, boolean cursiva) {
        SimpleAttributeSet estilo = new SimpleAttributeSet();
        StyleConstants.setForeground(estilo, color);
        StyleConstants.setBold(estilo, negrita);
        StyleConstants.setItalic(estilo, cursiva);
        return estilo;
    }
    /** Escaneo tolerante para pintar; la validez del programa la decide el analizador. */
    static List<Tramo> colorear(String fuente) {
        List<Tramo> tramos = new ArrayList<>();
        for (int i = 0; i < fuente.length();) {
            if (Thread.currentThread().isInterrupted()) return tramos;
            int inicio = i;
            char c = fuente.charAt(i);
            Color color = null;
            boolean negrita = false, cursiva = false;
            if (c == '/' && i + 1 < fuente.length() && fuente.charAt(i + 1) == '/') {
                while (i < fuente.length() && fuente.charAt(i) != '\n') i++;
                color = COMENTARIO; cursiva = true;
            } else if (c == '"') {
                i++;
                while (i < fuente.length() && fuente.charAt(i) != '"' && fuente.charAt(i) != '\n') i++;
                if (i < fuente.length() && fuente.charAt(i) == '"') i++;
                color = CADENA;
            } else if (letra(c)) {
                while (i < fuente.length() && (letra(fuente.charAt(i)) || digito(fuente.charAt(i)))) i++;
                String palabra = fuente.substring(inicio, i);
                if (Lexico.esPalabraClave(palabra)) {
                    color = palabra.matches("int|float|string|bool|list|void") ? TIPO : palabra.matches("true|false|null") ? NUMERO : CLAVE;
                    negrita = true;
                }
            } else if (digito(c)) {
                while (i < fuente.length() && digito(fuente.charAt(i))) i++;
                if (i + 1 < fuente.length() && fuente.charAt(i) == '.' && digito(fuente.charAt(i + 1))) {
                    i++;
                    while (i < fuente.length() && digito(fuente.charAt(i))) i++;
                }
                color = NUMERO;
            } else { i++; }
            if (color != null) tramos.add(new Tramo(inicio, i, color, negrita, cursiva));
        }
        return tramos;
    }
    private static boolean letra(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static boolean digito(char c) { return c >= '0' && c <= '9'; }
    static final class Tramo {
        final int inicio, fin;
        final Color color;
        final boolean negrita, cursiva;
        Tramo(int inicio, int fin, Color color, boolean negrita, boolean cursiva) {
            this.inicio = inicio; this.fin = fin; this.color = color; this.negrita = negrita; this.cursiva = cursiva;
        }
    }
    private final class NumerosLineas extends JComponent {
        @Override public Dimension getPreferredSize() {
            int digitos = String.valueOf(texto.getDocument().getDefaultRootElement().getElementCount()).length();
            return new Dimension(24 + Math.max(2, digitos) * texto.getFontMetrics(texto.getFont()).charWidth('0'), texto.getPreferredSize().height);
        }
        @Override protected void paintComponent(Graphics original) {
            Graphics2D g = (Graphics2D) original.create();
            g.setColor(new Color(248, 250, 252));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(texto.getFont());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Element raiz = texto.getDocument().getDefaultRootElement();
            Rectangle clip = g.getClipBounds();
            int primero = raiz.getElementIndex(texto.viewToModel2D(new Point(0, clip.y)));
            int ultimo = raiz.getElementIndex(texto.viewToModel2D(new Point(0, clip.y + clip.height)));
            int actual = raiz.getElementIndex(texto.getCaretPosition());
            for (int i = primero; i <= ultimo; i++) {
                try {
                    Rectangle2D r = texto.modelToView2D(raiz.getElement(i).getStartOffset());
                    if (r == null) continue;
                    g.setColor(i == actual ? new Color(37, 99, 235) : COMENTARIO);
                    String n = String.valueOf(i + 1);
                    g.drawString(n, getWidth() - 10 - g.getFontMetrics().stringWidth(n), (int) r.getY() + g.getFontMetrics().getAscent());
                } catch (BadLocationException ignored) { }
            }
            g.dispose();
        }
    }
}
