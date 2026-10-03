import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.*;

/** Casos de ámbitos reales, fragmentos incompletos e inserción a través del menú. */
public final class PruebasAutocompletado {
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        ejecutar();
        if (args.length > 0) renderizar(Paths.get(args[0]));
        System.out.println("OK: vocabulario, ámbitos, prefijos, menú, teclado e historial del autocompletado.");
    }
    static void ejecutar() {
        try {
            verificarMotor();
            verificarMenu();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void verificarMotor() {
        exigir(nombres("im«»").contains("import"), "Falta completar una palabra reservada.");
        exigir(nombres("Ex«»").contains("Exist") && !nombres("ex«»").contains("Exist"), "No se respeta la capitalización del lenguaje.");
        exigir(nombres("«»").containsAll(Lexico.palabrasClave()), "El vocabulario diverge del lexer.");
        exigir(nombres("// im«»").isEmpty() && nombres("string s = \"im«»").isEmpty(), "Se sugiere dentro de comentarios o cadenas abiertas.");
        exigir(nombres("string s = \"hola // int\"; im«»").contains("import"), "Se confundió un comentario dentro de una cadena con el código posterior.");
        exigir(nombres("objeto.im«»").isEmpty() && nombres("12im«»").isEmpty(), "Se sugieren miembros sin resolver o identificadores inválidos.");
        String cabecera = "{ class Main { int contador; void sumar(int parametro, list<int> valores) { ";
        exigir(nombres(cabecera + "int local = 1; «» } public main() { } } }").containsAll(Arrays.asList("parametro", "valores", "local", "contador", "sumar", "Main")), "Faltan parámetros, locales o miembros del ámbito.");
        exigir(!nombres(cabecera + "«» int futura = 2; } public main() { } } }").contains("futura"), "Se ofrece una local antes de declararla.");
        exigir(!nombres(cabecera + "int secreta = 2; } public main() { «» } } }").contains("secreta"), "Una local escapó de su método.");
        exigir(!nombres(cabecera + "if (true) { int interna = 1; } «» } public main() { } } }").contains("interna"), "Una variable escapó de un bloque cerrado.");
        exigir(nombres(cabecera + "int local = 1; if (true) { «» } } public main() { } } }").contains("local"), "Una rama no puede ver una local exterior.");
        exigir(nombres("{ class Main { public main() { «» } int posterior; } }").contains("posterior"), "Faltan campos declarados después del método.");
        exigir(!nombres("{ class A { int ajeno; public main() { } } class B { public main() { «» } } }").contains("ajeno"), "Se filtraron miembros de otra clase.");
        exigir(nombres(cabecera + "repeat each (int iterador in valores) { it«» } } public main() { } } }").contains("iterador"), "Falta el iterador en el cuerpo del ciclo.");
        exigir(!nombres(cabecera + "repeat each (int iterador in valores) { } it«» } public main() { } } }").contains("iterador"), "El iterador escapó de su ciclo.");
        exigir(nombres("import Persona; { class Main { public main() { Per«» } } }").contains("Persona"), "Falta el nombre del tipo importado.");
        Autocompletado.Resultado incompleto = resultado(cabecera + "list<list<int>> matriz; mat«»");
        exigir(incompleto.sugerencias.stream().anyMatch(s -> s.texto.equals("matriz") && s.detalle.contains("list<list<int>>")), "No se completan declaraciones genéricas con el bloque incompleto.");
        Autocompletado.Resultado oculto = resultado("{ class Main { int contador; public main() { string contador = \"x\"; co«» } } }");
        exigir(oculto.sugerencias.stream().filter(s -> s.texto.equals("contador")).count() == 1
                && oculto.sugerencias.stream().anyMatch(s -> s.texto.equals("contador") && s.detalle.equals("variable · string")), "No prevalece la declaración más cercana.");
        String fuente = "im«»portante";
        Autocompletado.Resultado mitad = resultado(fuente);
        exigir(mitad.inicio == 0 && mitad.fin == "importante".length() && mitad.cursor == 2, "El reemplazo en medio de una palabra no cubre su sufijo.");
        exigir(nombres("im«»").size() == 1, "El filtrado no coincide con el prefijo.");
    }

    private static void verificarMenu() throws Exception {
        EditorCodigo[] ref = new EditorCodigo[1];
        CountDownLatch listo = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            EditorCodigo editor = new EditorCodigo(); ref[0] = editor;
            editor.cargar(null, "importante");
            JTextPane texto = texto(editor);
            texto.setCaretPosition(2);
            esperarModelo(editor, listo);
            accion(texto, "completar-control SPACE");
        });
        exigir(listo.await(5, TimeUnit.SECONDS), "Ctrl+Espacio no genera sugerencias.");
        SwingUtilities.invokeAndWait(() -> {
            EditorCodigo editor = ref[0];
            exigir(editor.getCompletar().getLista().getModel().getElementAt(0).texto.equals("import"), "El menú no muestra el prefijo actual.");
            accion(texto(editor), "ENTER");
            exigir(editor.getTexto().equals("import"), "Enter no reemplaza la palabra completa o añadió una línea.");
            editor.resaltarAhora();
            editor.deshacer();
            exigir(editor.getTexto().equals("importante"), "Aceptar una sugerencia no se deshace en un solo paso.");
            editor.rehacer();
            exigir(editor.getTexto().equals("import"), "Rehacer no restaura la sugerencia.");
        });
        CountDownLatch varios = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            EditorCodigo editor = ref[0];
            editor.cargar(null, "i"); texto(editor).setCaretPosition(1);
            esperarModelo(editor, varios);
            editor.getCompletar().solicitar(true);
        });
        exigir(varios.await(5, TimeUnit.SECONDS), "No aparece un menú con varias opciones.");
        SwingUtilities.invokeAndWait(() -> {
            EditorCodigo editor = ref[0]; JTextPane texto = texto(editor);
            accion(texto, "completar-DOWN");
            exigir(editor.getCompletar().getLista().getSelectedIndex() == 1, "La flecha no navega por el menú.");
            String elegido = editor.getCompletar().getLista().getSelectedValue().texto;
            accion(texto, "TAB");
            exigir(editor.getTexto().equals(elegido), "Tab no inserta la opción seleccionada.");
            editor.cargar(null, "im"); texto.setCaretPosition(2);
            editor.getCompletar().solicitar(true);
            editor.cargar(null, "class Otro");
        });
        // El trabajador pendiente no puede insertar ni publicar opciones de un documento anterior.
        SwingUtilities.invokeAndWait(() -> {
            EditorCodigo editor = ref[0];
            exigir(!editor.getCompletar().aceptar() && editor.getTexto().equals("class Otro"), "Se aceptó una sugerencia obsoleta.");
            editor.cargar(null, "a\nb");
            editor.setSize(800, 400); distribuir(editor);
            // BasicTextUI habilita la navegación visual después de su primer pintado.
            Graphics2D pintado = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB).createGraphics();
            editor.printAll(pintado); pintado.dispose();
            texto(editor).setCaretPosition(0);
            accion(texto(editor), "completar-DOWN");
            exigir(texto(editor).getCaretPosition() > 0, "Sin menú, las flechas no mueven el cursor.");
            editor.cargar(null, "");
        });
    }
    private static void esperarModelo(EditorCodigo editor, CountDownLatch latch) {
        editor.getCompletar().getLista().getModel().addListDataListener(new ListDataListener() {
            public void intervalAdded(ListDataEvent e) { latch.countDown(); }
            public void intervalRemoved(ListDataEvent e) { }
            public void contentsChanged(ListDataEvent e) { }
        });
    }
    private static Autocompletado.Resultado resultado(String entrada) {
        int cursor = entrada.indexOf("«»");
        exigir(cursor >= 0, "Falta la marca del cursor.");
        return Autocompletado.sugerir(entrada.replace("«»", ""), cursor);
    }
    private static Set<String> nombres(String fuente) {
        Set<String> nombres = new HashSet<>();
        for (Autocompletado.Sugerencia s : resultado(fuente).sugerencias) nombres.add(s.texto);
        return nombres;
    }
    private static void accion(JTextPane texto, String nombre) {
        texto.getActionMap().get(nombre).actionPerformed(new java.awt.event.ActionEvent(texto, 0, nombre));
    }
    private static JTextPane texto(Container c) {
        for (Component hijo : c.getComponents()) {
            if (hijo instanceof JTextPane) return (JTextPane) hijo;
            if (hijo instanceof Container) { JTextPane t = texto((Container) hijo); if (t != null) return t; }
        }
        return null;
    }
    private static void distribuir(Container c) {
        c.doLayout();
        for (Component hijo : c.getComponents()) if (hijo instanceof Container) distribuir((Container) hijo);
    }
    private static void renderizar(Path archivo) throws Exception {
        EditorCodigo[] ref = new EditorCodigo[1];
        CountDownLatch listo = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            EditorCodigo editor = new EditorCodigo(); ref[0] = editor;
            editor.cargar(Paths.get("programa.txt"), "{\n  class Main {\n    int contador;\n    public main() {\n      int conteo = 1;\n      int contadorAuxiliar = 2;\n      co\n    }\n  }\n}\n");
            texto(editor).setCaretPosition(editor.getTexto().indexOf("      co\n") + 8);
            editor.setSize(1120, 520); distribuir(editor);
            esperarModelo(editor, listo);
            editor.getCompletar().solicitar(true);
        });
        exigir(listo.await(5, TimeUnit.SECONDS), "No se puede renderizar el menú.");
        SwingUtilities.invokeAndWait(() -> {
            try {
                EditorCodigo editor = ref[0];
                BufferedImage imagen = new BufferedImage(1120, 520, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = imagen.createGraphics(); editor.printAll(g);
                JPopupMenu popup = editor.getCompletar().getPopup(); popup.setSize(popup.getPreferredSize()); distribuir(popup);
                Rectangle2D r = texto(editor).modelToView2D(texto(editor).getCaretPosition());
                Point p = SwingUtilities.convertPoint(texto(editor), (int) r.getX(), (int) r.getMaxY(), editor);
                g.translate(p.x, p.y); popup.printAll(g); g.dispose();
                ImageIO.write(imagen, "png", archivo.toFile());
                editor.getCompletar().cerrar();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
