import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.util.*;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Tablas de consulta de cuádruplos y tercias, con explicación de la fila seleccionada. */
final class TablaDidacticaIr extends JTable {
    private final PresentacionDidacticaIr.Resultado resultado;
    private TablaDidacticaIr(PresentacionDidacticaIr.Resultado resultado, boolean triplos) {
        super(new Modelo(resultado, triplos));
        this.resultado = resultado;
        setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        setFillsViewportHeight(true); setRowHeight(25);
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        getTableHeader().setReorderingAllowed(false);
        int[] anchos = triplos ? new int[] {45, 180, 260, 235, 235, 85} : new int[] {45, 180, 260, 180, 180, 140, 85};
        for (int i = 0; i < anchos.length; i++) getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
    }
    static JComponent panel(PresentacionDidacticaIr.Resultado resultado, boolean triplos) {
        TablaDidacticaIr tabla = new TablaDidacticaIr(resultado, triplos);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JLabel ayuda = new JLabel(triplos
                ? "(n) identifica el resultado de la fila n. Las escrituras conservan su destino como argumento."
                : "(operador, argumento 1, argumento 2, resultado) · tN: valor · dN: dirección · LN: etiqueta");
        panel.add(ayuda, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(tabla);
        scroll.setColumnHeaderView(tabla.getTableHeader());
        panel.add(scroll, BorderLayout.CENTER);
        JLabel explicacion = new JLabel("Selecciona una fila para ver su equivalente de tres direcciones.");
        tabla.getSelectionModel().addListSelectionListener(e -> {
            int fila = tabla.getSelectedRow();
            if (fila >= 0) {
                String texto = resultado.filas.get(fila).numero + ": " + resultado.filas.get(fila).codigo();
                explicacion.setText(texto); explicacion.setToolTipText(texto);
            }
        });
        panel.add(explicacion, BorderLayout.SOUTH);
        return panel;
    }
    @Override public String getToolTipText(MouseEvent evento) {
        int fila = rowAtPoint(evento.getPoint()), columna = columnAtPoint(evento.getPoint());
        if (fila < 0 || columna < 0) return null;
        return String.valueOf(getValueAt(fila, columna)) + " · " + resultado.filas.get(fila).codigo();
    }
    private static final class Modelo extends AbstractTableModel {
        private final PresentacionDidacticaIr.Resultado resultado;
        private final boolean triplos;
        private final String[] columnas;
        Modelo(PresentacionDidacticaIr.Resultado resultado, boolean triplos) {
            this.resultado = resultado; this.triplos = triplos;
            columnas = triplos ? new String[] {"#", "Función", "Operador", "Argumento 1", "Argumento 2", "Ubicación"}
                    : new String[] {"#", "Función", "Operador", "Argumento 1", "Argumento 2", "Resultado", "Ubicación"};
        }
        @Override public int getRowCount() { return resultado.filas.size(); }
        @Override public int getColumnCount() { return columnas.length; }
        @Override public String getColumnName(int columna) { return columnas[columna]; }
        @Override public Class<?> getColumnClass(int columna) { return columna == 0 ? Integer.class : String.class; }
        @Override public Object getValueAt(int fila, int columna) {
            PresentacionDidacticaIr.Fila f = resultado.filas.get(fila);
            if (columna == 0) return f.numero;
            if (triplos) return resultado.triplos.get(fila).get(columna);
            switch (columna) {
                case 1: return f.funcion;
                case 2: return f.operacion;
                case 3: return f.argumento1.texto;
                case 4: return f.argumento2.texto;
                case 5: return f.resultado.texto;
                case 6: return f.ubicacion;
                default: throw new IndexOutOfBoundsException();
            }
        }
    }
}
