import java.awt.event.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;

public final class PruebasNavegacionErrores {
    public static void main(String[] args) {
        ejecutar();
        System.out.println("OK: navegación a línea y columna, doble clic y teclado en errores.");
    }

    static void ejecutar() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                EditorCodigo editor = new EditorCodigo();
                editor.cargar(null, "uno\ndos\n\nfin");
                JTextComponent texto = buscarTexto(editor);
                long revision = editor.getRevision();
                exigir(editor.irAUbicacion(2, 2) && texto.getCaretPosition() == 5,
                        "No se respetó la línea y columna.");
                exigir(editor.irAUbicacion(2, 99) && texto.getCaretPosition() == 7,
                        "La columna fuera de rango debe quedar al final de su línea.");
                exigir(editor.irAUbicacion(3, 1) && texto.getCaretPosition() == 8,
                        "No se pudo navegar a una línea vacía.");
                exigir(!editor.irAUbicacion(-1, 1) && !editor.irAUbicacion(9, 1)
                        && texto.getCaretPosition() == 8, "Se aceptó una línea inexistente.");
                exigir(editor.getRevision() == revision && !editor.tieneCambios(),
                        "Navegar modificó el documento.");

                JTable tabla = new JTable(new DefaultTableModel(new Object[][] {{2}, {1}}, new Object[] {"Línea"}));
                tabla.setAutoCreateRowSorter(true);
                tabla.getRowSorter().toggleSortOrder(0);
                tabla.setSize(200, 100);
                AtomicInteger destino = new AtomicInteger(-1);
                NavegacionErrores.conectar(tabla, destino::set);
                MouseEvent clic = new MouseEvent(tabla, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 2, false, MouseEvent.BUTTON1);
                for (MouseListener listener : tabla.getMouseListeners()) listener.mouseClicked(clic);
                exigir(destino.get() == 1, "El doble clic no tradujo la fila ordenada al modelo.");
                tabla.setRowSelectionInterval(1, 1);
                tabla.getActionMap().get("ir-al-error").actionPerformed(new ActionEvent(tabla, 0, ""));
                exigir(destino.get() == 0, "Enter no navegó a la fila seleccionada.");
                destino.set(-1);
                tabla.clearSelection();
                tabla.getActionMap().get("ir-al-error").actionPerformed(new ActionEvent(tabla, 0, ""));
                MouseEvent fondo = new MouseEvent(tabla, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 90, 2, false, MouseEvent.BUTTON1);
                for (MouseListener listener : tabla.getMouseListeners()) listener.mouseClicked(fondo);
                exigir(destino.get() == -1, "Se navegó sin una fila válida.");
            });
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static JTextComponent buscarTexto(java.awt.Container padre) {
        for (java.awt.Component hijo : padre.getComponents()) {
            if (hijo instanceof JTextComponent) return (JTextComponent) hijo;
            if (hijo instanceof java.awt.Container) {
                JTextComponent encontrado = buscarTexto((java.awt.Container) hijo);
                if (encontrado != null) return encontrado;
            }
        }
        return null;
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) throw new AssertionError(mensaje);
    }
}
