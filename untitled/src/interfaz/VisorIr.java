import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/** Vista de una instantánea del proyecto. La generación no construye componentes Swing. */
final class VisorIr extends JPanel {
    private final Resultado resultado;
    enum Vista {
        CUADRUPLOS_DIDACTICOS("Cuádruplos didácticos", "Proyecto.cuadruplos-didacticos.tsv"),
        TRIPLOS("Triplos (tercias)", "Proyecto.triplos.tsv"),
        SIMBOLICA("Tres direcciones", "Proyecto.simbolica.ir"),
        ATOMICA_SIMPLIFICADA("Atómica simplificada", "Proyecto.atomica-simplificada.ir"),
        FISICA("IR física", "Proyecto.ir"),
        TIPADA("IR tipada original", "Proyecto.tipada.ir"),
        CUADRUPLOS_FISICOS("Cuádruplos físicos", "Proyecto.cuadruplos-fisicos.tsv");
        final String titulo, archivo;
        Vista(String titulo, String archivo) { this.titulo = titulo; this.archivo = archivo; }
    }
    private final JTabbedPane vistas = new JTabbedPane();
    private final JTabbedPane tecnicas = new JTabbedPane();
    private final java.util.Map<java.awt.Component, Vista> documentos = new java.util.HashMap<>();
    private final JButton copiar = new JButton("Copiar IR");
    private final JButton guardar = new JButton("Guardar IR…");
    private final JLabel estado;
    private boolean desactualizado;

    VisorIr(Resultado resultado) {
        super(new BorderLayout(0, 8));
        this.resultado = resultado;
        setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel barra = new JPanel(new BorderLayout(8, 0));
        estado = new JLabel(resultado.resumen);
        estado.setToolTipText(resultado.resumen);
        barra.add(estado, BorderLayout.CENTER);
        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        acciones.add(copiar);
        acciones.add(guardar);
        barra.add(acciones, BorderLayout.EAST);
        add(barra, BorderLayout.NORTH);
        if (resultado.esExitoso()) {
            agregar(vistas, Vista.CUADRUPLOS_DIDACTICOS, TablaDidacticaIr.panel(resultado.didactica, false));
            agregar(vistas, Vista.TRIPLOS, TablaDidacticaIr.panel(resultado.didactica, true));
            agregar(vistas, Vista.SIMBOLICA, texto(resultado.didactica.codigo));
            agregar(vistas, Vista.ATOMICA_SIMPLIFICADA, texto(resultado.atomicaSimplificada));
            agregar(tecnicas, Vista.FISICA, texto(resultado.atomica));
            agregar(tecnicas, Vista.TIPADA, texto(resultado.tipada));
            TablaCuadruplosIr tabla = new TablaCuadruplosIr(resultado.cuadruplos);
            JScrollPane lista = new JScrollPane(tabla);
            lista.setColumnHeaderView(tabla.getTableHeader());
            agregar(tecnicas, Vista.CUADRUPLOS_FISICOS, lista);
            vistas.addTab("Detalles técnicos", tecnicas);
        } else {
            vistas.addTab("Diagnósticos IR", texto(resultado.diagnosticos));
        }
        add(vistas, BorderLayout.CENTER);
        copiar.setEnabled(resultado.esExitoso());
        guardar.setEnabled(resultado.esExitoso());
        copiar.addActionListener(e -> copiar());
        guardar.addActionListener(e -> guardar());
        EstiloInterfaz.aplicar(this);
    }

    private static JScrollPane texto(String contenido) {
        JTextArea texto = new JTextArea(contenido);
        texto.setEditable(false);
        texto.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        texto.setLineWrap(false);
        texto.setCaretPosition(0);
        return new JScrollPane(texto);
    }

    private void agregar(JTabbedPane contenedor, Vista vista, JComponent componente) {
        documentos.put(componente, vista);
        contenedor.addTab(vista.titulo, componente);
    }

    Vista vistaSeleccionada() {
        java.awt.Component seleccionado = vistas.getSelectedComponent();
        return documentos.get(seleccionado == tecnicas ? tecnicas.getSelectedComponent() : seleccionado);
    }

    void mostrarVista(Vista vista) {
        for (java.util.Map.Entry<java.awt.Component, Vista> entrada : documentos.entrySet()) if (entrada.getValue() == vista) {
            if (entrada.getKey().getParent() == tecnicas) {
                vistas.setSelectedComponent(tecnicas); tecnicas.setSelectedComponent(entrada.getKey());
            } else vistas.setSelectedComponent(entrada.getKey());
            return;
        }
        throw new IllegalArgumentException("Vista IR no disponible");
    }

    String textoSeleccionado() { return resultado.texto(vistaSeleccionada()); }

    void marcarDesactualizado() {
        desactualizado = true;
        estado.setText("IR desactualizada. Vuelve a analizar para copiar o guardar.");
        estado.setToolTipText(estado.getText());
        copiar.setEnabled(false);
        guardar.setEnabled(false);
    }

    private void copiar() {
        if (desactualizado || !resultado.esExitoso()) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(textoSeleccionado()), null);
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, "No se pudo copiar la IR: " + e.getMessage(),
                    "Copiar IR", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void guardar() {
        if (desactualizado || !resultado.esExitoso()) return;
        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Guardar la IR de la instantánea analizada");
        Vista vista = vistaSeleccionada();
        selector.setSelectedFile(new java.io.File(vista.archivo));
        if (selector.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destino = selector.getSelectedFile().toPath();
        try {
            resultado.validarDestino(destino);
            if (Files.exists(destino) && JOptionPane.showConfirmDialog(this,
                    "El archivo ya existe. ¿Deseas reemplazarlo?", "Guardar IR",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            resultado.guardar(destino, vista);
        } catch (IOException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar la IR: " + e.getMessage(),
                    "Guardar IR", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Ejecutar en el trabajador del análisis, con sus resultados finales y su fuente del editor. */
    static Resultado generar(List<Main.ResultadoAnalisis> unidades, Path principal) {
        List<Path> fuentes = new ArrayList<>();
        StringBuilder errores = new StringBuilder();
        for (Main.ResultadoAnalisis unidad : unidades) {
            fuentes.add(unidad.archivo);
            if (unidad.resultadoSemantico == null || !unidad.resultadoSemantico.esExitoso()) {
                errores.append(unidad.archivo).append(": ").append(unidad.estadoDiagnosticos()).append('\n');
                for (compiladorErrores error : unidad.diagnosticos()) errores.append(error).append('\n');
                for (String error : unidad.erroresImport) errores.append(error).append('\n');
            }
        }
        if (errores.length() > 0) return Resultado.fallo(fuentes,
                "Corrige los errores del proyecto y vuelve a analizar para generar la IR.\n\n" + errores);
        try {
            // La vista incluye todos los métodos. Su entrada de referencia prioriza el archivo del editor.
            List<Main.ResultadoAnalisis> orden = new ArrayList<>(unidades);
            Path ruta = principal.toAbsolutePath().normalize();
            orden.sort(java.util.Comparator.comparing(u -> !u.archivo.toAbsolutePath().normalize().equals(ruta)));
            Set<String> entradas = new LinkedHashSet<>();
            for (Main.ResultadoAnalisis unidad : orden) recogerEntradas(unidad.resultadoSemantico.getAstValidado(), entradas);
            String entrada = entradas.isEmpty() ? null : entradas.iterator().next();
            ResultadoGeneracionIr generacion = new GeneradorIr().generar(unidades, entrada);
            if (!generacion.esExitoso()) return Resultado.fallo(fuentes, String.join("\n", generacion.errores));
            ModuloAtomicoIr atomica = new LinealizadorIr().transformar(generacion.modulo);
            String textoAtomico = new ImpresorAtomicoIr().imprimir(atomica);
            String textoTipado = new ImpresorIr().imprimir(generacion.modulo);
            List<TablaCuadruplosIr.Fila> cuadruplos = TablaCuadruplosIr.filas(atomica);
            PresentacionDidacticaIr.Resultado didactica = PresentacionDidacticaIr.desdeTipada(generacion.modulo);
            String simplificada = PresentacionDidacticaIr.atomicaSimplificada(generacion.modulo, atomica);
            String resumen = didactica.filas.size() + " filas didácticas · " + cuadruplos.size()
                    + " cuádruplos físicos · Entrada: " + generacion.modulo.entrada.propietaria;
            if (entradas.size() > 1) resumen += " (varios main; se muestran todos)";
            return new Resultado(fuentes, textoAtomico, textoTipado, "", resumen, cuadruplos, didactica, simplificada, generacion.modulo);
        } catch (RuntimeException e) {
            return Resultado.fallo(fuentes, "No se pudo generar o verificar la IR: " + e.getMessage());
        }
    }

    private static void recogerEntradas(NodoArbolSintactico ast, Set<String> entradas) {
        Deque<NodoArbolSintactico> pendientes = new ArrayDeque<>();
        pendientes.push(ast);
        while (!pendientes.isEmpty()) {
            NodoArbolSintactico nodo = pendientes.pop();
            Semantico.Simbolo simbolo = nodo.getSimboloResuelto();
            if (nodo.getNombre().equals("METODO") && simbolo != null && simbolo.nombre.equals("main")
                    && simbolo.visibilidad == Semantico.Visibilidad.PUBLICO && simbolo.tipo.equals(Semantico.VOID)
                    && simbolo.parametros.isEmpty()) entradas.add(simbolo.propietaria.simbolo.nombre);
            List<NodoArbolSintactico> hijos = nodo.getHijos();
            for (int i = hijos.size() - 1; i >= 0; i--) pendientes.push(hijos.get(i));
        }
    }

    static final class Resultado {
        final List<Path> fuentes;
        final ModuloIr modulo;
        final String atomica, tipada, diagnosticos, resumen;
        final List<TablaCuadruplosIr.Fila> cuadruplos;
        final String cuadruplosTsv, atomicaSimplificada;
        final PresentacionDidacticaIr.Resultado didactica;

        private Resultado(List<Path> fuentes, String atomica, String tipada, String diagnosticos, String resumen,
                List<TablaCuadruplosIr.Fila> cuadruplos, PresentacionDidacticaIr.Resultado didactica, String atomicaSimplificada, ModuloIr modulo) {
            this.modulo = modulo;
            this.fuentes = Collections.unmodifiableList(new ArrayList<>(fuentes));
            this.atomica = atomica;
            this.tipada = tipada;
            this.diagnosticos = diagnosticos;
            this.resumen = resumen;
            this.didactica = didactica; this.atomicaSimplificada = atomicaSimplificada;
            this.cuadruplos = Collections.unmodifiableList(new ArrayList<>(cuadruplos));
            cuadruplosTsv = atomica.isEmpty() ? "" : TablaCuadruplosIr.tsv(this.cuadruplos);
        }

        static Resultado fallo(List<Path> fuentes, String diagnosticos) {
            return new Resultado(fuentes, "", "", diagnosticos, "IR no disponible para este proyecto", Collections.emptyList(), null, "", null);
        }

        boolean esExitoso() { return !atomica.isEmpty() && diagnosticos.isEmpty(); }

        void validarDestino(Path destino) throws IOException {
            if (!esExitoso()) throw new IllegalArgumentException("No hay una IR válida para guardar");
            for (Path fuente : fuentes)
                if (destino.toAbsolutePath().normalize().equals(fuente.toAbsolutePath().normalize())
                        || Files.exists(destino) && Files.exists(fuente) && Files.isSameFile(destino, fuente))
                    throw new IllegalArgumentException("La IR no puede sobrescribir una unidad fuente");
        }

        void guardar(Path destino, boolean vistaTipada) throws IOException {
            guardar(destino, vistaTipada ? 1 : 0);
        }

        String texto(int vista) {
            switch (vista) {
                case 0: return atomica;
                case 1: return tipada;
                case 2: return cuadruplosTsv;
                default: throw new IllegalArgumentException("Vista IR desconocida");
            }
        }

        String texto(Vista vista) {
            switch (vista) {
                case CUADRUPLOS_DIDACTICOS: return didactica.cuadruplosTsv;
                case TRIPLOS: return didactica.triplosTsv;
                case SIMBOLICA: return didactica.codigo;
                case ATOMICA_SIMPLIFICADA: return atomicaSimplificada;
                case FISICA: return atomica;
                case TIPADA: return tipada;
                case CUADRUPLOS_FISICOS: return cuadruplosTsv;
                default: throw new IllegalArgumentException("Vista IR desconocida");
            }
        }

        void guardar(Path destino, Vista vista) throws IOException {
            validarDestino(destino);
            Files.writeString(destino, texto(vista), StandardCharsets.UTF_8);
        }

        void guardar(Path destino, int vista) throws IOException {
            validarDestino(destino);
            Files.writeString(destino, texto(vista), StandardCharsets.UTF_8);
        }
    }
}
