import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.*;

/** Prueba las interacciones reales del explorador Swing sin requerir una pantalla. */
public final class PruebasVisorAst {
    private static final String FUENTE = "{\nclass Main {\npublic main() {\nint x = 1;\nfloat y = x;\nx += 2;\n}\n}\n}";

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        ejecutar();
        if (args.length > 0) {
            Main.ResultadoAnalisis demo = Main.analizarProyecto(Paths.get("untitled/ejemplos/programa_demo.txt"), false).get(0);
            Path destino = Paths.get(args[0]);
            Files.createDirectories(destino);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    capturar(new VisorAst(demo.sintactico.getArbol(), false, demo.source, false), destino.resolve("ast-sintactico.png"), 1500, 760);
                    VisorAst semantico = new VisorAst(demo.sintactico.getArbol(), true, demo.source, false);
                    seleccionarNombre(semantico, "DECLARACION");
                    pulsar(semantico, "Enfocar rama");
                    capturar(semantico, destino.resolve("ast-semantico.png"), 1500, 760);
                    capturar(semantico, destino.resolve("ast-compacto.png"), 1120, 420);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        System.out.println("OK: selección, conversiones, búsqueda, plegado, zoom y ubicación del AST.");
    }

    static void ejecutar() {
        Path archivo = null;
        try {
            archivo = Files.createTempFile("visor-ast-", ".txt");
            Files.writeString(archivo, FUENTE);
            Main.ResultadoAnalisis resultado = Main.analizarProyecto(archivo, false).get(0);
            exigir(resultado.resultadoSemantico.esExitoso(), "La entrada del visor debe ser válida.");
            SwingUtilities.invokeAndWait(() -> comprobar(resultado));
        } catch (Exception e) { throw new AssertionError(e); }
        finally { if (archivo != null) try { Files.delete(archivo); } catch (Exception e) { throw new AssertionError(e); } }
    }

    private static void comprobar(Main.ResultadoAnalisis resultado) {
        String antes = resultado.sintactico.getArbol().imprimirSemantico();
        VisorAst sintactico = new VisorAst(resultado.sintactico.getArbol(), false, FUENTE, false);
        VisorAst visor = new VisorAst(resultado.sintactico.getArbol(), true, FUENTE, false);
        preparar(visor, 1500, 760);
        pulsar(visor, "Ajustar");
        JTree tree = encontrar(visor, JTree.class).get(0);
        exigir(nombres(tree).stream().noneMatch(s -> s.startsWith("Acción:")), "Las acciones no deben convertirse en nodos AST.");
        exigir(nombres(tree).stream().anyMatch(s -> s.startsWith("Conversión implícita")), "Falta la arista semántica de conversión.");
        exigir(nombres(encontrar(sintactico, JTree.class).get(0)).stream().noneMatch(s -> s.startsWith("Conversión implícita")), "La vista sintáctica fue alterada por la semántica.");

        seleccionarNombre(visor, "CONVERSION_IMPLICITA");
        exigir(textos(visor).contains("Tipo: float"), "No se muestra el tipo de la conversión seleccionada.");
        exigir(textos(visor).contains("Nombre AST: CONVERSION_IMPLICITA"), "No se seleccionó la conversión.");
        pulsar(visor, "Enfocar rama");
        exigir(textos(visor).contains("Rama: Conversión implícita"), "No se aisló la rama seleccionada.");
        exigir(encontrar(visor, JTextArea.class).stream().anyMatch(a -> a.getHighlighter().getHighlights().length == 1), "No se resalta la ubicación del nodo en el código.");

        JTextField buscar = encontrar(visor, JTextField.class).get(0);
        buscar.setText("identificador");
        pulsar(visor, "Siguiente");
        TreePath primero = tree.getSelectionPath();
        pulsar(visor, "Siguiente");
        exigir(!primero.equals(tree.getSelectionPath()), "La búsqueda no navega entre coincidencias.");
        buscar.setText("un-nodo-inexistente");
        exigir(!boton(visor, "Siguiente").isEnabled(), "La búsqueda sin resultados debe quedar desactivada.");
        buscar.setText("");

        pulsar(visor, "Inicio");
        exigir(!textos(visor).contains("Rama: Conversión implícita"), "Inicio no recupera el árbol completo.");
        pulsar(visor, "Plegar rama");
        exigir(textos(visor).contains("1 de "), "Plegar la raíz no oculta sus descendientes.");
        pulsar(visor, "Expandir rama");
        pulsar(visor, "Expandir todo");
        int cantidad = nombres(tree).size();
        exigir(textos(visor).contains(cantidad + " de " + cantidad + " nodos visibles"), "Expandir todo no recupera todos los nodos.");
        pulsar(visor, "Resumir");
        seleccionarNombre(visor, "CONVERSION_IMPLICITA");
        exigir(textos(visor).contains("Nombre AST: CONVERSION_IMPLICITA"), "El índice no revela un nodo oculto.");
        double antesZoom = porcentaje(visor);
        pulsar(visor, "+");
        exigir(porcentaje(visor) > antesZoom, "El botón de zoom no aumenta la escala.");
        pulsar(visor, "−");
        exigir(Math.abs(porcentaje(visor) - antesZoom) <= 1, "El zoom no vuelve a la escala anterior.");
        JComponent lienzo = encontrar(visor, JComponent.class).stream().filter(c -> c.getClass().getSimpleName().equals("Lienzo")).findFirst().orElseThrow();
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, lienzo);
        java.awt.event.MouseWheelEvent rueda = new java.awt.event.MouseWheelEvent(scroll, java.awt.event.MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), java.awt.event.InputEvent.CTRL_DOWN_MASK, 100, 100, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1);
        for (java.awt.event.MouseWheelListener listener : scroll.getMouseWheelListeners()) listener.mouseWheelMoved(rueda);
        exigir(rueda.isConsumed() && porcentaje(visor) > antesZoom, "Ctrl + rueda debe aumentar el zoom sin desplazar.");
        pulsar(visor, "Inicio");
        lienzo.getActionMap().get("RIGHT").actionPerformed(new java.awt.event.ActionEvent(lienzo, 0, "RIGHT"));
        exigir(tree.getSelectionPath().getPathCount() == 2, "La flecha derecha no navega hacia un hijo.");
        lienzo.getActionMap().get("LEFT").actionPerformed(new java.awt.event.ActionEvent(lienzo, 0, "LEFT"));
        exigir(tree.getSelectionPath().getPathCount() == 1, "La flecha izquierda no vuelve al padre.");
        exigir(antes.equals(resultado.sintactico.getArbol().imprimirSemantico()), "El visor modificó el AST del motor.");
        NodoArbolSintactico error = new NodoArbolSintactico("ERROR_EXPRESION", "falta operando", 4, 1);
        error.marcarSintaxisIncompleta();
        VisorAst parcial = new VisorAst(error, true, FUENTE, true);
        exigir(textos(parcial).contains("AST parcial") && textos(parcial).contains("Sintaxis incompleta:"), "La vista parcial oculta su estado.");
    }

    private static void seleccionarNombre(Container visor, String nombre) {
        JTree tree = encontrar(visor, JTree.class).get(0);
        java.util.Enumeration<?> nodos = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        String legible = nombre.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        while (nodos.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodos.nextElement();
            String texto = java.text.Normalizer.normalize(n.toString().toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            if (texto.startsWith(legible)) {
                tree.setSelectionPath(new TreePath(n.getPath()));
                return;
            }
        }
        throw new AssertionError("No existe el nodo " + nombre);
    }

    private static List<String> nombres(JTree tree) {
        List<String> resultado = new ArrayList<>();
        java.util.Enumeration<?> nodos = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodos.hasMoreElements()) resultado.add(nodos.nextElement().toString());
        return resultado;
    }
    private static double porcentaje(Container c) {
        for (JLabel l : encontrar(c, JLabel.class)) if (l.getText().matches("[0-9]+ %")) return Double.parseDouble(l.getText().replace(" %", ""));
        throw new AssertionError("Falta el indicador de zoom.");
    }
    private static String textos(Container c) {
        StringBuilder s = new StringBuilder();
        for (JLabel l : encontrar(c, JLabel.class)) s.append(l.getText()).append('\n');
        for (JTextArea a : encontrar(c, JTextArea.class)) s.append(a.getText()).append('\n');
        return s.toString();
    }
    private static JButton boton(Container c, String texto) {
        for (JButton b : encontrar(c, JButton.class)) if (texto.equals(b.getText())) return b;
        throw new AssertionError("Falta el botón " + texto);
    }
    private static void pulsar(Container c, String texto) { boton(c, texto).doClick(0); }
    private static <T extends Component> List<T> encontrar(Container c, Class<T> tipo) {
        List<T> resultado = new ArrayList<>();
        for (Component hijo : c.getComponents()) {
            if (tipo.isInstance(hijo)) resultado.add(tipo.cast(hijo));
            if (hijo instanceof Container) resultado.addAll(encontrar((Container) hijo, tipo));
        }
        return resultado;
    }
    private static void preparar(Container c, int ancho, int alto) {
        c.setSize(ancho, alto);
        distribuir(c);
    }
    private static void distribuir(Container c) {
        c.doLayout();
        for (Component hijo : c.getComponents()) if (hijo instanceof Container) distribuir((Container) hijo);
    }
    private static void capturar(VisorAst visor, Path archivo, int ancho, int alto) throws Exception {
        preparar(visor, ancho, alto);
        pulsar(visor, "Ajustar");
        distribuir(visor);
        BufferedImage imagen = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        visor.printAll(g);
        g.dispose();
        ImageIO.write(imagen, "png", archivo.toFile());
    }
    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) throw new AssertionError(mensaje);
    }
}
