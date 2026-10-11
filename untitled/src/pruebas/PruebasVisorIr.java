import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Integración editor/proyecto/IR y presentación Swing verificable sin pantalla. */
public final class PruebasVisorIr {
    public static void main(String[] args) throws Exception { ejecutar(); }

    static void ejecutar() {
        Path carpeta = null;
        try {
            carpeta = Files.createTempDirectory("visor-ir-");
            Path principal = carpeta.resolve("Main.txt");
            String disco = "{ class Main { public main() { int x = 1; } } }";
            Files.writeString(principal, disco);
            String editor = "import runtime.Consola; { class Main { public main() { "
                    + "int x = 987654321 + 2 * 3; Consola c = new Consola(); c.imprimirLinea(\"Árbol\"); "
                    + "while (true) { x += 1; } } } }";
            List<Main.ResultadoAnalisis> unidades = Main.analizarProyecto(principal, editor, false);
            VisorIr.Resultado valido = VisorIr.generar(unidades, principal);
            exigir(valido.esExitoso(), "La vista rechazó el contenido válido del editor: " + valido.diagnosticos);
            exigir(valido.atomica.contains("987654321:int") && valido.atomica.contains("[rbp-")
                    && valido.atomica.contains("LLAMAR_RUNTIME") && valido.atomica.contains("JUMP_IF_FALSE"),
                    "La IR no incluye el contenido actual, offsets, runtime o saltos");
            exigir(valido.atomica.indexOf("(MUL,") < valido.atomica.indexOf("(ADD,"), "Se perdió la atomicidad de la expresión");
            exigir(Files.readString(principal).equals(disco), "Generar la vista guardó el editor sobre la fuente");
            ResultadoGeneracionIr canonico = new GeneradorIr().generar(unidades, "Main");
            exigir(valido.atomica.equals(new ImpresorAtomicoIr().imprimir(new LinealizadorIr().transformar(canonico.modulo)))
                    && valido.tipada.equals(new ImpresorIr().imprimir(canonico.modulo)),
                    "La vista difiere de los exportadores canónicos");
            verificarPanel(unidades, valido);

            Path salida = carpeta.resolve("salida.ir");
            valido.guardar(salida, false);
            exigir(Files.readString(salida, StandardCharsets.UTF_8).equals(valido.atomica), "Exportación atómica incompleta");
            valido.guardar(salida, true);
            exigir(Files.readString(salida, StandardCharsets.UTF_8).equals(valido.tipada), "Exportación tipada incompleta");
            valido.guardar(salida, 2);
            exigir(Files.readString(salida, StandardCharsets.UTF_8).equals(valido.cuadruplosTsv)
                    && valido.cuadruplosTsv.startsWith("#\tFunción\tOperación\tFuente 1\tFuente 2\tDestino\tUbicación\n"),
                    "Exportación de la lista de cuádruplos incompleta");
            exigir(valido.cuadruplosTsv.lines().count() == valido.cuadruplos.size() + 1L,
                    "La tabla TSV contiene filas adicionales o faltantes");
            for (String fila : valido.cuadruplosTsv.split("\n"))
                exigir(fila.split("\t", -1).length == 7, "Una celda rompió las columnas de la exportación");
            for (VisorIr.Vista vista : VisorIr.Vista.values()) {
                valido.guardar(salida, vista);
                exigir(Files.readString(salida).equals(valido.texto(vista)), "Exportación incompleta: " + vista);
                try { valido.guardar(principal, vista); throw new AssertionError("Sobrescritura fuente: " + vista); }
                catch (IllegalArgumentException esperado) { }
            }
            rechazarSobrescritura(valido, principal);
            Path enlace = carpeta.resolve("enlace.ir");
            Files.createSymbolicLink(enlace, principal.getFileName());
            rechazarSobrescritura(valido, enlace);
            Files.delete(enlace);
            Files.createLink(enlace, principal);
            rechazarSobrescritura(valido, enlace);
            exigir(Files.readString(principal).equals(disco), "Se sobrescribió una fuente a través de su alias");

            Path sinGuardar = carpeta.resolve("Sin_titulo.txt");
            List<Main.ResultadoAnalisis> nuevo = Main.analizarProyecto(sinGuardar, disco, false);
            VisorIr.Resultado irNuevo = VisorIr.generar(nuevo, sinGuardar);
            exigir(irNuevo.esExitoso(), "Un documento nuevo requiere guardarse para ver su IR");
            rechazarSobrescritura(irNuevo, sinGuardar);
            exigir(!Files.exists(sinGuardar), "La vista creó el archivo de un documento sin guardar");

            Files.writeString(carpeta.resolve("Aux.txt"), "{ class Aux { public void f() { } public main() { int dato = 37; } } }");
            String conImport = "import Aux; { class Main { public main() { Aux a = new Aux(); a.f(); } } }";
            unidades = Main.analizarProyecto(principal, conImport, true);
            VisorIr.Resultado proyecto = VisorIr.generar(unidades, principal);
            exigir(proyecto.esExitoso() && proyecto.atomica.contains("clase Aux ") && proyecto.atomica.contains("clase Main ")
                    && proyecto.atomica.contains("entrada " + LayoutProgramaIr.nombreFuncion(new FirmaIr("Main", "main",
                            TipoIr.VOID, FirmaIr.Visibilidad.PUBLICA, java.util.Collections.emptyList())))
                    && proyecto.resumen.contains("varios main"),
                    "La vista debe incluir los imports y priorizar el main del editor: " + proyecto.diagnosticos);
            rechazarSobrescritura(proyecto, carpeta.resolve("Aux.txt"));
            exigir(proyecto.cuadruplos.stream().anyMatch(f -> f.celda(1).toString().startsWith("Aux::"))
                    && proyecto.cuadruplos.stream().anyMatch(f -> f.celda(1).toString().startsWith("Main::")),
                    "La lista omitió los métodos de una unidad importada");
            verificarPanel(unidades, proyecto);

            for (String cuerpo : new String[] { "int x = @;", "int x = ;", "int x = true;" }) {
                unidades = Main.analizarProyecto(principal, "{ class Main { public main() { " + cuerpo + " } } }", false);
                verificarRechazo(unidades, principal, salida);
            }
            Files.writeString(carpeta.resolve("Aux.txt"), "{ class Aux { public void f() { } public main() { int x = true; } } }");
            unidades = Main.analizarProyecto(principal, conImport, true);
            verificarRechazo(unidades, principal, salida);
            unidades = Main.analizarProyecto(principal, "import Falta; " + disco, true);
            verificarRechazo(unidades, principal, salida);
            System.out.println("OK: visor IR, instantánea del editor, imports, diagnósticos y exportación protegida.");
        } catch (Exception e) { throw new AssertionError(e); }
        finally {
            if (carpeta != null) try (java.util.stream.Stream<Path> archivos = Files.walk(carpeta)) {
                for (Path archivo : (Iterable<Path>) archivos.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.delete(archivo);
            } catch (Exception e) { throw new AssertionError(e); }
        }
    }

    private static void verificarRechazo(List<Main.ResultadoAnalisis> unidades, Path principal, Path salida) throws Exception {
        VisorIr.Resultado rechazo = VisorIr.generar(unidades, principal);
        exigir(!rechazo.esExitoso() && rechazo.atomica.isEmpty() && rechazo.tipada.isEmpty()
                && rechazo.cuadruplos.isEmpty() && rechazo.cuadruplosTsv.isEmpty()
                && rechazo.didactica == null && rechazo.atomicaSimplificada.isEmpty()
                && !rechazo.diagnosticos.isEmpty(), "Un proyecto inválido expuso una IR parcial");
        String anterior = Files.readString(salida);
        rechazarSobrescritura(rechazo, salida);
        exigir(Files.readString(salida).equals(anterior), "Un fallo de generación sobrescribió una exportación anterior");
        verificarPanel(unidades, rechazo);
    }

    private static void rechazarSobrescritura(VisorIr.Resultado ir, Path destino) throws Exception {
        try { ir.guardar(destino, false); throw new AssertionError("Se permitió sobrescribir: " + destino); }
        catch (IllegalArgumentException esperado) { }
    }

    private static void verificarPanel(List<Main.ResultadoAnalisis> unidades, VisorIr.Resultado ir) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTabbedPane pestanas = new JTabbedPane();
            pestanas.addTab("IR anterior", new JTextArea("resultado obsoleto"));
            AnalizadorLexicoUI.ResumenResultados resumen = AnalizadorLexicoUI.llenarPestanas(
                    pestanas, unidades, u -> new JPanel(), ir);
            exigir(pestanas.getTabCount() == unidades.size() + 1
                    && pestanas.getTitleAt(unidades.size()).equals("IR del proyecto"),
                    "La IR no está integrada a Resultados o conservó pestañas anteriores");
            exigir(resumen.errores == unidades.stream().mapToInt(u -> AnalizadorLexicoUI.crearErrores(u).size()).sum(),
                    "La pestaña IR alteró el conteo de errores del frontend");
            VisorIr visor = (VisorIr) pestanas.getComponentAt(unidades.size());
            List<JTextArea> textos = componentes(visor, JTextArea.class);
            List<JTable> tablas = componentes(visor, JTable.class);
            List<JButton> botones = componentes(visor, JButton.class);
            botones.removeIf(b -> !"Copiar IR".equals(b.getText()) && !"Guardar IR…".equals(b.getText()));
            exigir(botones.size() == 2, "Faltan las acciones de copiar y guardar IR");
            exigir(textos.size() == (ir.esExitoso() ? 4 : 1), "Número inesperado de vistas IR");
            for (JTextArea texto : textos) exigir(!texto.isEditable() && !texto.getLineWrap(), "La IR permite modificar su salida");
            for (JButton boton : botones) exigir(boton.isEnabled() == ir.esExitoso(), "Exportación/copia habilitada sin IR válida");
            if (ir.esExitoso()) {
                exigir(tablas.size() == 3, "Faltan tablas didácticas o físicas");
                JTable tabla = tablas.stream().filter(t -> t instanceof TablaCuadruplosIr).findFirst().orElseThrow();
                javax.swing.JScrollPane scroll = (javax.swing.JScrollPane) tabla.getParent().getParent();
                exigir(scroll.getColumnHeader() != null && scroll.getColumnHeader().getView() == tabla.getTableHeader(),
                        "La lista no muestra los encabezados de las columnas");
                List<String> cuads = ir.atomica.lines().map(String::stripLeading)
                        .filter(linea -> linea.startsWith("(")).collect(java.util.stream.Collectors.toList());
                exigir(tabla.getRowCount() == cuads.size() && tabla.getColumnCount() == 7,
                        "La tabla omitió cuádruplos o columnas");
                for (int fila = 0; fila < tabla.getRowCount(); fila++) {
                    exigir(tabla.getValueAt(fila, 0).equals(fila + 1), "Se alteró el orden de los cuádruplos");
                    String representacion = "(" + tabla.getValueAt(fila, 2) + ", " + tabla.getValueAt(fila, 3)
                            + ", " + tabla.getValueAt(fila, 4) + ", " + tabla.getValueAt(fila, 5)
                            + ") @ " + tabla.getValueAt(fila, 6);
                    exigir(cuads.get(fila).equals(representacion), "La lista de cuádruplos difiere de la IR generada");
                    for (int columna = 0; columna < tabla.getColumnCount(); columna++)
                        exigir(!tabla.isCellEditable(fila, columna), "La tabla permite modificar la IR");
                }
                exigir(visor.vistaSeleccionada() == VisorIr.Vista.CUADRUPLOS_DIDACTICOS
                        && visor.textoSeleccionado().equals(ir.didactica.cuadruplosTsv), "La vista inicial debe ser didáctica");
                for (VisorIr.Vista vista : VisorIr.Vista.values()) {
                    visor.mostrarVista(vista);
                    exigir(visor.vistaSeleccionada() == vista && visor.textoSeleccionado().equals(ir.texto(vista)),
                            "Copiar/guardar no respeta la vista seleccionada: " + vista);
                }
                exigir(textos.stream().anyMatch(t -> t.getText().equals(ir.atomica))
                        && textos.stream().anyMatch(t -> t.getText().equals(ir.tipada))
                        && textos.stream().anyMatch(t -> t.getText().equals(ir.didactica.codigo))
                        && textos.stream().anyMatch(t -> t.getText().equals(ir.atomicaSimplificada)),
                        "Las vistas ocultaron una representación original o didáctica");
                for (JTable t : tablas) for (int fila = 0; fila < t.getRowCount(); fila++)
                    for (int columna = 0; columna < t.getColumnCount(); columna++)
                        exigir(!t.isCellEditable(fila, columna), "Una vista didáctica permite editar la IR");
                visor.marcarDesactualizado();
                for (JButton boton : botones) exigir(!boton.isEnabled(), "Una IR desactualizada todavía puede copiarse/exportarse");
            } else {
                exigir(tablas.isEmpty(), "Un proyecto inválido expuso cuádruplos de un análisis anterior");
                exigir(textos.get(0).getText().equals(ir.diagnosticos), "Se ocultaron los motivos del rechazo");
            }
        });
    }

    private static <T extends Component> List<T> componentes(Component raiz, Class<T> tipo) {
        List<T> lista = new ArrayList<>();
        if (tipo.isInstance(raiz)) lista.add(tipo.cast(raiz));
        if (raiz instanceof Container) for (Component hijo : ((Container) raiz).getComponents()) lista.addAll(componentes(hijo, tipo));
        return lista;
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) throw new AssertionError(mensaje);
    }
}
