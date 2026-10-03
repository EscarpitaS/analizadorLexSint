import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Comprueba edición, historial, colores y análisis de buffers sin tocar la fuente guardada. */
public final class PruebasEditorCodigo {
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        ejecutar();
        if (args.length > 0) {
            String ejemplo = Files.readString(Paths.get("untitled/ejemplos/programa_demo.txt"));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    EditorCodigo editor = new EditorCodigo();
                    editor.cargar(Paths.get("programa_demo.txt"), "// Código del lenguaje: edita y pulsa F5 para analizar\n" + ejemplo);
                    editor.setSize(1120, 520);
                    distribuir(editor);
                    BufferedImage imagen = new BufferedImage(1120, 520, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = imagen.createGraphics();
                    editor.printAll(g); g.dispose();
                    ImageIO.write(imagen, "png", Paths.get(args[0]).toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        System.out.println("OK: resaltado, deshacer/rehacer, sangría, guardado UTF-8 y análisis sin guardar.");
    }

    static void ejecutar() {
        Path carpeta = null;
        try {
            carpeta = Files.createTempDirectory("editor-codigo-");
            Path destino = carpeta.resolve("programa.txt");
            SwingUtilities.invokeAndWait(() -> verificarEdicion(destino));
            verificarResaltadoAsincrono();
            verificarAnalisisEnMemoria(carpeta);
        } catch (Exception e) { throw new AssertionError(e); }
        finally {
            if (carpeta != null) try (java.util.stream.Stream<Path> archivos = Files.walk(carpeta)) {
                for (Path archivo : (Iterable<Path>) archivos.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(archivo);
            } catch (Exception e) { throw new AssertionError(e); }
        }
    }

    private static void verificarEdicion(Path destino) {
        try {
            EditorCodigo editor = new EditorCodigo();
            JTextPane texto = buscarTexto(editor);
            String fuente = "class Main {\r\n  int importador = 42;\r\n  string mensaje = \"hola // if\"; // comentario int 7\r\n  float x = 3.14; bool activo = true;\r\n  Exist(x) { }\r\n}\r\n";
            editor.cargar(destino, fuente);
            String normalizado = fuente.replace("\r\n", "\n");
            exigir(normalizado.equals(editor.getTexto()) && !editor.tieneCambios(), "Carga o normalización de líneas incorrecta.");
            exigir(!boton(editor, "Deshacer").isEnabled(), "Abrir un archivo dejó historial anterior.");
            comprobarColor(texto, "class", EditorCodigo.CLAVE);
            comprobarColor(texto, "int", EditorCodigo.TIPO);
            comprobarColor(texto, "importador", EditorCodigo.NORMAL);
            comprobarColor(texto, "Main", EditorCodigo.NORMAL);
            comprobarColor(texto, "Exist", EditorCodigo.CLAVE);
            comprobarColor(texto, "42", EditorCodigo.NUMERO);
            comprobarColor(texto, "3.14", EditorCodigo.NUMERO);
            comprobarColor(texto, "true", EditorCodigo.NUMERO);
            comprobarColor(texto, "hola // if", EditorCodigo.CADENA);
            comprobarColor(texto, "comentario int 7", EditorCodigo.COMENTARIO);
            long revision = editor.getRevision();
            editor.resaltarAhora();
            exigir(editor.getRevision() == revision && !editor.tieneCambios(), "Los colores alteraron el estado del documento.");

            texto.setCaretPosition(texto.getDocument().getLength());
            texto.replaceSelection("// café\r\n");
            exigir(editor.tieneCambios(), "Editar no marca cambios pendientes.");
            editor.resaltarAhora();
            editor.deshacer();
            exigir(normalizado.equals(editor.getTexto()) && !editor.tieneCambios(), "Deshacer incluyó atributos de color o no restauró el texto.");
            editor.rehacer();
            exigir(editor.getTexto().endsWith("// café\n"), "Rehacer no restauró el cambio.");
            editor.guardar(destino);
            exigir(Files.readString(destino).equals(editor.getTexto()) && !editor.tieneCambios(), "Guardado UTF-8 incorrecto.");
            texto.setCaretPosition(0);
            texto.replaceSelection("// otra edición\n");
            editor.resaltarAhora();
            editor.deshacer();
            exigir(!editor.tieneCambios(), "Deshacer hasta la versión guardada no limpia el estado.");
            exigir(boton(editor, "Rehacer").isEnabled(), "Resaltar o guardar vació el historial.");

            editor.cargar(null, "  if (true) {");
            texto.setCaretPosition(texto.getDocument().getLength());
            accion(texto, "ENTER");
            exigir(editor.getTexto().endsWith("\n      "), "Enter no conserva y aumenta la sangría del bloque.");
            accion(texto, "shift TAB");
            exigir(editor.getTexto().endsWith("\n  "), "Shift+Tab no quita cuatro espacios.");
            editor.cargar(null, "int x;\nfloat y;\n");
            texto.select(0, texto.getDocument().getLength());
            accion(texto, "TAB");
            exigir(editor.getTexto().equals("    int x;\n    float y;\n"), "La sangría de una selección altera líneas o añade espacios al final.");
            editor.deshacer();
            exigir(editor.getTexto().equals("int x;\nfloat y;\n"), "La sangría no se deshace en un solo paso.");

            editor.cargar(null, "string s = \"sin cerrar\nint x = 1;\n");
            comprobarColor(texto, "sin cerrar", EditorCodigo.CADENA);
            comprobarColor(texto, "int", EditorCodigo.TIPO);
            editor.cargar(null, "// " + "a".repeat(400) + "\n");
            editor.setSize(600, 300);
            distribuir(editor);
            Rectangle2D inicio = texto.modelToView2D(0), fin = texto.modelToView2D(350);
            exigir(inicio != null && fin != null && inicio.getY() == fin.getY(), "El editor envuelve una línea larga y desalineó su numeración.");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void verificarResaltadoAsincrono() throws Exception {
        CountDownLatch pintado = new CountDownLatch(1);
        EditorCodigo[] referencia = new EditorCodigo[1];
        SwingUtilities.invokeAndWait(() -> {
            EditorCodigo editor = new EditorCodigo();
            referencia[0] = editor;
            JTextPane texto = buscarTexto(editor);
            texto.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { }
                public void removeUpdate(DocumentEvent e) { }
                public void changedUpdate(DocumentEvent e) {
                    if (texto.getDocument().getLength() == 3 && color(texto, 0).equals(EditorCodigo.TIPO)) pintado.countDown();
                }
            });
            texto.replaceSelection("class");
            texto.select(0, 5);
            texto.replaceSelection("int");
        });
        exigir(pintado.await(5, TimeUnit.SECONDS), "El resaltado automático no pintó la última edición.");
        SwingUtilities.invokeAndWait(() -> {
            exigir(referencia[0].getTexto().equals("int"), "Un trabajador obsoleto alteró el texto.");
            referencia[0].deshacer();
            exigir(referencia[0].getTexto().equals("class"), "La edición de reemplazo no conserva historial tras el resaltado automático.");
            referencia[0].cargar(null, "");
        });
    }

    private static void verificarAnalisisEnMemoria(Path carpeta) throws Exception {
        Path principal = carpeta.resolve("Main.txt");
        String guardado = "{ class Main { public main() { int x = 1; } } }";
        String editado = "{ class Main { public main() { int x = true; } } }";
        Files.writeString(principal, guardado);
        Main.ResultadoAnalisis resultado = Main.analizarProyecto(principal, editado, false).get(0);
        exigir(resultado.source.equals(editado) && resultado.semantico.hasErrors(), "El análisis leyó el disco en vez del buffer.");
        exigir(Files.readString(principal).equals(guardado), "Analizar escribió cambios no guardados.");
        Path nuevo = carpeta.resolve("Sin_titulo.txt");
        exigir(Main.analizarProyecto(nuevo, guardado, false).get(0).resultadoSemantico.esExitoso() && !Files.exists(nuevo), "No se puede analizar un documento nuevo sin crearlo en disco.");
        Path persona = carpeta.resolve("Persona.txt");
        String dependencia = "{ class Persona { public main() { } } }";
        Files.writeString(persona, dependencia);
        String conImport = "import Persona; { class Main { public main() { Persona p = new Persona(); } } }";
        List<Main.ResultadoAnalisis> proyecto = Main.analizarProyecto(principal, conImport, true);
        exigir(proyecto.size() == 2 && proyecto.stream().allMatch(r -> r.resultadoSemantico.esExitoso()), "Los imports no se resuelven desde la carpeta del buffer.");
        exigir(proyecto.stream().anyMatch(r -> r.source.equals(conImport)) && Files.readString(principal).equals(guardado)
                && Files.readString(persona).equals(dependencia), "El análisis con imports cambió archivos o perdió el buffer raíz.");
    }
    private static Color color(JTextPane texto, int offset) {
        return StyleConstants.getForeground(texto.getStyledDocument().getCharacterElement(offset).getAttributes());
    }
    private static void comprobarColor(JTextPane texto, String fragmento, Color esperado) {
        int offset = texto.getText().indexOf(fragmento);
        exigir(offset >= 0 && color(texto, offset).equals(esperado), "Color incorrecto para " + fragmento);
    }
    private static void accion(JTextPane texto, String nombre) {
        texto.getActionMap().get(nombre).actionPerformed(new java.awt.event.ActionEvent(texto, 0, nombre));
    }
    private static JTextPane buscarTexto(Container c) {
        for (Component hijo : c.getComponents()) {
            if (hijo instanceof JTextPane) return (JTextPane) hijo;
            if (hijo instanceof Container) { JTextPane t = buscarTexto((Container) hijo); if (t != null) return t; }
        }
        return null;
    }
    private static JButton boton(Container c, String nombre) {
        for (Component hijo : c.getComponents()) {
            if (hijo instanceof JButton && nombre.equals(((JButton) hijo).getText())) return (JButton) hijo;
            if (hijo instanceof Container) { JButton b = boton((Container) hijo, nombre); if (b != null) return b; }
        }
        return null;
    }
    private static void distribuir(Container c) {
        c.doLayout();
        for (Component hijo : c.getComponents()) if (hijo instanceof Container) distribuir((Container) hijo);
    }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
