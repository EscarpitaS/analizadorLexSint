import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.util.function.LongSupplier;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.BadLocationException;

/** Menú no modal de sugerencias; nunca quita el foco al texto. */
final class CompletarEditor {
    private final JTextPane texto;
    private final LongSupplier revision;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<Autocompletado.Sugerencia> modelo = new DefaultListModel<>();
    private final JList<Autocompletado.Sugerencia> lista = new JList<>(modelo);
    private final Timer espera = new Timer(180, e -> solicitar(false));
    private SwingWorker<Autocompletado.Resultado, Void> trabajador;
    private Autocompletado.Resultado resultado;
    private long versionResultado, solicitud;
    private boolean activo;

    CompletarEditor(JTextPane texto, LongSupplier revision) {
        this.texto = texto;
        this.revision = revision;
        espera.setRepeats(false);
        popup.setFocusable(false);
        popup.setLayout(new BorderLayout());
        JLabel instrucciones = new JLabel("  ↑ ↓ elegir · Enter / Tab insertar · Esc cerrar  ");
        instrucciones.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        instrucciones.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        popup.add(instrucciones, BorderLayout.NORTH);
        lista.setFocusable(false);
        lista.setSelectionBackground(new Color(37, 99, 235));
        lista.setSelectionForeground(Color.WHITE);
        lista.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lista.setFixedCellHeight(30);
        lista.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object valor, int i, boolean seleccionado, boolean foco) {
                super.getListCellRendererComponent(l, valor, i, seleccionado, foco);
                Autocompletado.Sugerencia s = (Autocompletado.Sugerencia) valor;
                setText("<html><b>" + html(s.texto) + "</b> &nbsp; " + html(s.detalle) + "</html>");
                setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(lista);
        scroll.setFocusable(false);
        scroll.setBorder(null);
        popup.add(scroll, BorderLayout.CENTER);
        lista.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int i = lista.locationToIndex(e.getPoint());
                if (i >= 0 && lista.getCellBounds(i, i).contains(e.getPoint())) { lista.setSelectedIndex(i); aceptar(); }
            }
        });
        popup.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) { }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { activo = false; }
            public void popupMenuCanceled(PopupMenuEvent e) { cerrar(); }
        });
        texto.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { programar(); }
            public void removeUpdate(DocumentEvent e) { programar(); }
            public void changedUpdate(DocumentEvent e) { }
        });
        texto.addCaretListener(e -> {
            if (resultado != null && resultado.cursor != texto.getCaretPosition()) cerrar();
            if (espera.isRunning()) espera.restart();
        });
        texto.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { cerrar(); }
        });
        texto.addHierarchyListener(e -> { if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !texto.isShowing()) cerrar(); });
        tecla("control SPACE", () -> solicitar(true));
        navegar("UP", -1);
        navegar("DOWN", 1);
        navegar("PAGE_UP", -8);
        navegar("PAGE_DOWN", 8);
        Object accionEscape = texto.getInputMap().get(KeyStroke.getKeyStroke("ESCAPE"));
        Action escape = accionEscape == null ? null : texto.getActionMap().get(accionEscape);
        tecla("ESCAPE", () -> { if (activo) cerrar(); else if (escape != null) escape.actionPerformed(evento()); });
    }

    private void programar() { cerrar(); espera.restart(); }
    void solicitar(boolean manual) {
        espera.stop();
        if (!manual && (!texto.isShowing() || !texto.hasFocus())) return;
        if (texto.getSelectionStart() != texto.getSelectionEnd()) { cerrar(); return; }
        String fuente = texto.getText();
        int cursor = texto.getCaretPosition();
        int inicio = cursor;
        while (inicio > 0 && Character.isLetterOrDigit(fuente.charAt(inicio - 1))) inicio--;
        if (!manual && cursor - inicio < 2) { cerrar(); return; }
        if (trabajador != null) trabajador.cancel(true);
        final long ticket = ++solicitud, version = revision.getAsLong();
        trabajador = new SwingWorker<>() {
            @Override protected Autocompletado.Resultado doInBackground() { return Autocompletado.sugerir(fuente, cursor); }
            @Override protected void done() {
                if (isCancelled() || ticket != solicitud || revision.getAsLong() != version || cursor != texto.getCaretPosition()) return;
                try { mostrar(get(), version); } catch (Exception e) { cerrar(); }
            }
        };
        trabajador.execute();
    }
    private void mostrar(Autocompletado.Resultado nuevo, long version) throws BadLocationException {
        if (nuevo.sugerencias.isEmpty()) { cerrar(); return; }
        resultado = nuevo;
        versionResultado = version;
        modelo.clear();
        for (Autocompletado.Sugerencia s : nuevo.sugerencias) modelo.addElement(s);
        lista.setSelectedIndex(0);
        lista.setVisibleRowCount(Math.min(8, modelo.size()));
        popup.setPopupSize(440, 34 + Math.min(8, modelo.size()) * 30);
        if (texto.isShowing()) {
            Rectangle2D posicion = texto.modelToView2D(texto.getCaretPosition());
            if (posicion == null) { cerrar(); return; }
            popup.show(texto, (int) posicion.getX(), (int) posicion.getMaxY());
        }
        activo = true;
    }
    boolean aceptar() {
        if (!activo || resultado == null || lista.getSelectedValue() == null) return false;
        if (versionResultado != revision.getAsLong() || resultado.cursor != texto.getCaretPosition()
                || texto.getSelectionStart() != texto.getSelectionEnd()) { cerrar(); return false; }
        Autocompletado.Resultado actual = resultado;
        String insertar = lista.getSelectedValue().texto;
        cerrar();
        texto.select(actual.inicio, actual.fin);
        texto.replaceSelection(insertar);
        texto.requestFocusInWindow();
        return true;
    }
    void cerrar() {
        solicitud++;
        activo = false;
        resultado = null;
        espera.stop();
        if (trabajador != null) trabajador.cancel(true);
        popup.setVisible(false);
    }
    private void navegar(String tecla, int direccion) {
        Object nombre = texto.getInputMap().get(KeyStroke.getKeyStroke(tecla));
        Action anterior = nombre == null ? null : texto.getActionMap().get(nombre);
        tecla(tecla, () -> {
            if (activo) {
                int i = Math.max(0, Math.min(modelo.size() - 1, lista.getSelectedIndex() + direccion));
                lista.setSelectedIndex(i);
                lista.ensureIndexIsVisible(i);
            } else if (anterior != null) anterior.actionPerformed(evento());
        });
    }
    private void tecla(String tecla, Runnable accion) {
        String nombre = "completar-" + tecla;
        texto.getInputMap().put(KeyStroke.getKeyStroke(tecla), nombre);
        texto.getActionMap().put(nombre, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { accion.run(); }
        });
    }
    private ActionEvent evento() { return new ActionEvent(texto, ActionEvent.ACTION_PERFORMED, ""); }
    private static String html(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    // Permite verificar y renderizar el mismo menú en un entorno sin pantalla.
    JList<Autocompletado.Sugerencia> getLista() { return lista; }
    JPopupMenu getPopup() { return popup; }
}
