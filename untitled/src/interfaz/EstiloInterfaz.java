import java.awt.*;
import javax.swing.*;

/** Paleta neutra de Nimbus, limitada a los controles externos al editor y AST. */
final class EstiloInterfaz {
    private EstiloInterfaz() {}

    static void aplicar(Component componente) {
        if (componente instanceof EditorCodigo || componente instanceof VisorAst) return;
        if (componente instanceof JComponent) {
            JComponent control = (JComponent) componente;
            UIDefaults colores = new UIDefaults();
            colores.put("nimbusBase", new Color(90, 90, 90));
            colores.put("nimbusBlueGrey", new Color(230, 230, 230));
            colores.put("nimbusFocus", new Color(80, 80, 80));
            colores.put("control", new Color(250, 250, 250));
            colores.put("text", Color.BLACK);
            colores.put("nimbusLightBackground", Color.WHITE);
            colores.put("nimbusSelectionBackground", new Color(220, 220, 220));
            colores.put("nimbusSelectedText", Color.BLACK);
            control.putClientProperty("Nimbus.Overrides", colores);
            control.putClientProperty("Nimbus.Overrides.InheritDefaults", true);
            control.setForeground(Color.BLACK);
            if (control instanceof JTabbedPane) {
                ((JTabbedPane) control).setUI(new PestanasNeutras());
            } else if (control instanceof JTable) {
                JTable tabla = (JTable) control;
                tabla.setSelectionBackground(new Color(220, 220, 220));
                tabla.setSelectionForeground(Color.BLACK);
                tabla.setGridColor(new Color(230, 230, 230));
            } else if (control instanceof javax.swing.text.JTextComponent) {
                javax.swing.text.JTextComponent texto = (javax.swing.text.JTextComponent) control;
                texto.setSelectionColor(new Color(220, 220, 220));
                texto.setSelectedTextColor(Color.BLACK);
                texto.setCaretColor(Color.BLACK);
            }
        }
        if (componente instanceof Container) {
            for (Component hijo : ((Container) componente).getComponents()) aplicar(hijo);
        }
    }

    private static final class PestanasNeutras extends javax.swing.plaf.basic.BasicTabbedPaneUI {
        @Override protected void paintTabBackground(Graphics g, int posicion, int indice,
                int x, int y, int ancho, int alto, boolean seleccionada) {
            g.setColor(seleccionada ? Color.WHITE : new Color(240, 240, 240));
            g.fillRect(x, y, ancho, alto);
        }

        @Override protected void paintTabBorder(Graphics g, int posicion, int indice,
                int x, int y, int ancho, int alto, boolean seleccionada) {
            g.setColor(new Color(215, 215, 215));
            g.drawRect(x, y, ancho - 1, alto - 1);
            if (seleccionada) {
                g.setColor(Color.BLACK);
                g.fillRect(x, y, ancho, 2);
            }
        }

        @Override protected void paintContentBorder(Graphics g, int posicion, int seleccionado) {
            // Los paneles ya proporcionan su propio borde neutro.
        }

        @Override protected void paintFocusIndicator(Graphics g, int posicion, Rectangle[] rectangulos,
                int indice, Rectangle icono, Rectangle texto, boolean seleccionada) {
            if (tabPane.hasFocus() && seleccionada) {
                Rectangle r = rectangulos[indice];
                g.setColor(Color.BLACK);
                javax.swing.plaf.basic.BasicGraphicsUtils.drawDashedRect(g, r.x + 4, r.y + 4,
                        r.width - 8, r.height - 8);
            }
        }
    }
}
