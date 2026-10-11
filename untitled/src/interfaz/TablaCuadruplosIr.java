import java.awt.Font;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/** Presentación de los cuádruplos existentes en su orden original, sin modificar la IR. */
final class TablaCuadruplosIr extends JTable {
    private static final String[] COLUMNAS = { "#", "Función", "Operación", "Fuente 1", "Fuente 2", "Destino", "Ubicación" };

    TablaCuadruplosIr(List<Fila> filas) {
        super(new Modelo(filas));
        setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        setFillsViewportHeight(true);
        setRowHeight(25);
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        getTableHeader().setReorderingAllowed(false);
        int[] anchos = { 45, 170, 140, 235, 150, 235, 85 };
        for (int i = 0; i < anchos.length; i++) getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
    }

    @Override public String getToolTipText(MouseEvent evento) {
        int fila = rowAtPoint(evento.getPoint()), columna = columnAtPoint(evento.getPoint());
        return fila < 0 || columna < 0 ? null : String.valueOf(getValueAt(fila, columna));
    }

    /** Crear las filas en el trabajador del análisis, a partir del módulo ya verificado. */
    static List<Fila> filas(ModuloAtomicoIr modulo) {
        List<Fila> filas = new ArrayList<>();
        ImpresorAtomicoIr impresor = new ImpresorAtomicoIr();
        for (FuncionAtomicaIr funcion : modulo.funciones) {
            LayoutProgramaIr.Marco marco = modulo.layout.marcos.get(funcion.firma.id());
            for (CuadruploIr q : funcion.codigo) filas.add(new Fila(filas.size() + 1, funcion.firma.id(), q.operacion.name(),
                    impresor.operando(q.fuente1, marco), impresor.operando(q.fuente2, marco), impresor.operando(q.destino, marco),
                    ":" + q.origen.linea + ":" + q.origen.columna));
        }
        return Collections.unmodifiableList(filas);
    }

    static String tsv(List<Fila> filas) {
        StringBuilder texto = new StringBuilder(String.join("\t", COLUMNAS)).append('\n');
        for (Fila fila : filas) {
            texto.append(fila.numero);
            for (String celda : fila.celdas) texto.append('\t').append(celda);
            texto.append('\n');
        }
        return texto.toString();
    }

    static final class Fila {
        final int numero;
        private final List<String> celdas;
        Fila(int numero, String funcion, String operacion, String fuente1, String fuente2, String destino, String ubicacion) {
            this.numero = numero;
            celdas = List.of(funcion, operacion, fuente1, fuente2, destino, ubicacion);
        }
        Object celda(int columna) { return columna == 0 ? numero : celdas.get(columna - 1); }
    }

    private static final class Modelo extends AbstractTableModel {
        private final List<Fila> filas;
        Modelo(List<Fila> filas) { this.filas = Collections.unmodifiableList(new ArrayList<>(filas)); }
        @Override public int getRowCount() { return filas.size(); }
        @Override public int getColumnCount() { return COLUMNAS.length; }
        @Override public String getColumnName(int columna) { return COLUMNAS[columna]; }
        @Override public Class<?> getColumnClass(int columna) { return columna == 0 ? Integer.class : String.class; }
        @Override public Object getValueAt(int fila, int columna) { return filas.get(fila).celda(columna); }
    }
}
