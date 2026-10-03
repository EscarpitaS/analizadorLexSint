import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Acciones y resumen del análisis integrados en el editor. */
final class BarraAnalisis extends JPanel {
    BarraAnalisis(JCheckBox imports, JButton analizar, JButton reporte,
                  JLabel archivos, JLabel tokens, JLabel errores) {
        super(new BorderLayout(10, 0));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(230, 230, 230)),
                new EmptyBorder(6, 0, 0, 0)));
        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        acciones.setOpaque(false);
        acciones.add(imports);
        acciones.add(analizar);
        acciones.add(reporte);
        JPanel resumen = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        resumen.setOpaque(false);
        resumen.add(indicador("Archivos", archivos));
        resumen.add(indicador("Tokens", tokens));
        resumen.add(indicador("Errores", errores));
        add(acciones, BorderLayout.WEST);
        add(resumen, BorderLayout.EAST);
    }

    private JPanel indicador(String texto, JLabel valor) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        panel.setOpaque(false);
        valor.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        valor.setForeground(Color.BLACK);
        JLabel etiqueta = new JLabel(texto);
        etiqueta.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        etiqueta.setForeground(Color.BLACK);
        panel.add(etiqueta);
        panel.add(valor);
        return panel;
    }
}
