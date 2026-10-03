import java.awt.event.*;
import java.util.function.IntConsumer;
import javax.swing.*;

/** Navega usando el índice del modelo, aunque la tabla cambie su orden visual. */
final class NavegacionErrores {
    private NavegacionErrores() {}

    static void conectar(JTable tabla, IntConsumer navegar) {
        tabla.setToolTipText("Doble clic o Enter para ir a la ubicación del error en el editor.");
        tabla.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent evento) {
                if (evento.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(evento)) return;
                int fila = tabla.rowAtPoint(evento.getPoint());
                if (fila >= 0) navegar.accept(tabla.convertRowIndexToModel(fila));
            }
        });
        tabla.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ir-al-error");
        tabla.getActionMap().put("ir-al-error", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent evento) {
                int fila = tabla.getSelectedRow();
                if (fila >= 0) navegar.accept(tabla.convertRowIndexToModel(fila));
            }
        });
    }
}
