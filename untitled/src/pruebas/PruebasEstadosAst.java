import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.*;

/** Verifica diagnósticos del motor y sus colores con nodos seleccionados. */
public final class PruebasEstadosAst {
    private static final Color SINTAXIS = new Color(220, 38, 38);
    private static final Color INALCANZABLE = new Color(180, 83, 9);
    private static final Color SEMANTICA = new Color(190, 24, 93);
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        ejecutar(args.length == 0 ? null : Paths.get(args[0]));
        System.out.println("OK: estados, errores semánticos y colores persistentes al seleccionar el AST.");
    }
    static void ejecutar() { ejecutar(null); }
    private static void ejecutar(Path salida) {
        try {
            if (salida != null) Files.createDirectories(salida);
            verificar("int x = true;", SEMANTICA, "Error semántico", salida, "error-semantico");
            verificar("return; int x = 1;", INALCANZABLE, "Inalcanzable", salida, "inalcanzable");
            verificar("int x = ;", SINTAXIS, "Sintaxis incompleta", salida, "sintaxis-incompleta");
            Main.ResultadoAnalisis herencia = Main.analizarProyecto(Paths.get("Main.txt"),
                    "{ class Main extends Main { public main() { } } }", false).get(0);
            exigir(buscarNodo(herencia.sintactico.getArbol(), "CLASE").tieneErrorSemantico(),
                    "Un error de símbolo no quedó enlazado a la declaración de clase.");
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void verificar(String cuerpo, Color color, String estado, Path salida, String nombre) throws Exception {
        String fuente = "{\nclass Main {\npublic main() {\n" + cuerpo + "\n}\n}\n}";
        Path archivo = Files.createTempFile("estados-ast-", ".txt");
        try {
            Files.writeString(archivo, fuente);
            Main.ResultadoAnalisis resultado = Main.analizarProyecto(archivo, false).get(0);
            NodoArbolSintactico declaracion = buscarNodo(resultado.sintactico.getArbol(), "DECLARACION");
            exigir(declaracion != null, "La recuperación perdió la declaración del caso " + nombre);
            NodoArbolSintactico diagnosticado = color.equals(SEMANTICA) ? buscarErrorSemantico(resultado.sintactico.getArbol()) : declaracion;
            if (color.equals(SEMANTICA)) {
                exigir(diagnosticado != null && diagnosticado.getNombre().equals("IDENTIFICADOR") && !diagnosticado.esSintaxisIncompleta(), "La incompatibilidad de tipos no está enlazada al nodo correcto.");
                List<String> mensajes = new ArrayList<>(diagnosticado.getErroresSemanticos());
                resultado.semantico.analizar(resultado.sintactico.getArbol());
                exigir(mensajes.equals(diagnosticado.getErroresSemanticos()), "Repetir el análisis duplicó los errores del nodo.");
                NodoArbolSintactico copia = new NodoArbolSintactico("IDENTIFICADOR", "x");
                copia.registrarErrorSemantico("ejemplo"); copia.limpiarSemantica();
                exigir(!copia.tieneErrorSemantico(), "Limpiar anotaciones conserva un diagnóstico obsoleto.");
            } else if (color.equals(INALCANZABLE)) {
                exigir(declaracion.esInalcanzable() && declaracion.tieneErrorSemantico(), "El retorno no marcó la sentencia posterior.");
            } else exigir(declaracion.esSintaxisIncompleta(), "La declaración incompleta no conserva su marcador.");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    VisorAst visor = new VisorAst(resultado.sintactico.getArbol(), true, fuente, resultado.sintactico.hasErrors());
                    visor.setSize(1500, 760); distribuir(visor);
                    JTree indice = encontrar(visor, JTree.class).get(0);
                    String etiqueta = color.equals(SEMANTICA) ? "Identificador: x" : "Declaración";
                    DefaultMutableTreeNode nodo = buscarSwing(indice, etiqueta);
                    indice.setSelectionPath(new TreePath(nodo.getPath()));
                    pulsar(visor, "Enfocar rama"); pulsar(visor, "Ajustar"); distribuir(visor);
                    Component seleccionado = indice.getCellRenderer().getTreeCellRendererComponent(indice, nodo, true, true, false, 0, true);
                    exigir(seleccionado.getForeground().equals(color), "Seleccionar en el índice oculta el estado " + estado);
                    exigir(etiquetaRender(seleccionado).contains(estado), "Falta la etiqueta de estado en el índice.");
                    Component normal = indice.getCellRenderer().getTreeCellRendererComponent(indice, nodo, false, true, false, 0, false);
                    exigir(normal.getForeground().equals(color), "El índice sin selección pierde su color.");
                    String detalles = textoDetalles(visor);
                    exigir(detalles.contains("Estado: " + estado), "Falta el estado en el inspector.");
                    if (color.equals(SEMANTICA)) {
                        exigir(detalles.contains("Errores semánticos del nodo") && detalles.contains(diagnosticado.getErroresSemanticos().get(0)), "El inspector no muestra el diagnóstico real.");
                        VisorAst sintactico = new VisorAst(resultado.sintactico.getArbol(), false, fuente, false);
                        JTree otro = encontrar(sintactico, JTree.class).get(0);
                        Component render = otro.getCellRenderer().getTreeCellRendererComponent(otro, buscarSwing(otro, etiqueta), true, true, false, 0, true);
                        exigir(!render.getForeground().equals(SEMANTICA), "La vista sintáctica mezcla errores semánticos.");
                    }
                    JComponent lienzo = encontrar(visor, JComponent.class).stream().filter(c -> c.getClass().getSimpleName().equals("Lienzo")).findFirst().orElseThrow();
                    BufferedImage dibujo = imagen(lienzo);
                    exigir(contarColor(dibujo, color) > 150, "El estado seleccionado no tiene un borde visible en el diagrama.");
                    exigir(contarColor(dibujo, new Color(37, 99, 235)) > 100, "La selección no se distingue del estado del nodo.");
                    if (salida != null) ImageIO.write(imagen(visor), "png", salida.resolve(nombre + ".png").toFile());
                    if (color.equals(SINTAXIS)) {
                        DefaultMutableTreeNode identificador = buscarSwing(indice, "Identificador: x");
                        indice.setSelectionPath(new TreePath(identificador.getPath()));
                        Component contexto = indice.getCellRenderer().getTreeCellRendererComponent(indice, identificador, true, false, true, 0, true);
                        exigir(contexto.getForeground().equals(SINTAXIS) && etiquetaRender(contexto).contains("Fragmento incompleto"), "Una hoja oculta el estado del fragmento contenedor.");
                        exigir(textoDetalles(visor).contains("El marcador corresponde al fragmento contenedor"), "No se distingue contexto incompleto de error propio.");
                        // El nodo de clase es sano aunque la raíz tenga el indicador global de AST parcial.
                        DefaultMutableTreeNode sano = buscarSwing(indice, "Identificador: Main");
                        Component valido = indice.getCellRenderer().getTreeCellRendererComponent(indice, sano, false, false, true, 0, false);
                        exigir(!valido.getForeground().equals(SINTAXIS), "El marcador global de AST parcial coloreó un fragmento sano.");
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally { Files.delete(archivo); }
    }
    private static NodoArbolSintactico buscarNodo(NodoArbolSintactico nodo, String nombre) {
        if (nodo.getNombre().equals(nombre)) return nodo;
        for (NodoArbolSintactico hijo : nodo.getHijos()) { NodoArbolSintactico n = buscarNodo(hijo, nombre); if (n != null) return n; }
        return null;
    }
    private static NodoArbolSintactico buscarErrorSemantico(NodoArbolSintactico nodo) {
        if (nodo.tieneErrorSemantico()) return nodo;
        for (NodoArbolSintactico hijo : nodo.getHijos()) { NodoArbolSintactico n = buscarErrorSemantico(hijo); if (n != null) return n; }
        return null;
    }
    private static DefaultMutableTreeNode buscarSwing(JTree tree, String nombre) {
        java.util.Enumeration<?> recorrido = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (recorrido.hasMoreElements()) { DefaultMutableTreeNode n = (DefaultMutableTreeNode) recorrido.nextElement(); if (n.toString().startsWith(nombre)) return n; }
        throw new AssertionError("Falta el nodo " + nombre);
    }
    private static String textoDetalles(Container c) {
        StringBuilder s = new StringBuilder();
        for (JTextArea area : encontrar(c, JTextArea.class)) s.append(area.getText()).append('\n');
        return s.toString();
    }
    private static String etiquetaRender(Component c) {
        if (c instanceof JLabel) return ((JLabel) c).getText();
        return encontrar((Container) c, JLabel.class).get(0).getText();
    }
    private static BufferedImage imagen(JComponent c) {
        BufferedImage imagen = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics(); c.printAll(g); g.dispose(); return imagen;
    }
    private static int contarColor(BufferedImage imagen, Color color) {
        int n = 0;
        for (int y = 0; y < imagen.getHeight(); y++) for (int x = 0; x < imagen.getWidth(); x++) if (imagen.getRGB(x, y) == color.getRGB()) n++;
        return n;
    }
    private static void pulsar(Container c, String texto) {
        for (JButton boton : encontrar(c, JButton.class)) if (texto.equals(boton.getText())) { boton.doClick(0); return; }
        throw new AssertionError("Falta " + texto);
    }
    private static <T extends Component> List<T> encontrar(Container c, Class<T> tipo) {
        List<T> resultado = new ArrayList<>();
        for (Component hijo : c.getComponents()) {
            if (tipo.isInstance(hijo)) resultado.add(tipo.cast(hijo));
            if (hijo instanceof Container) resultado.addAll(encontrar((Container) hijo, tipo));
        }
        return resultado;
    }
    private static void distribuir(Container c) { c.doLayout(); for (Component hijo : c.getComponents()) if (hijo instanceof Container) distribuir((Container) hijo); }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
