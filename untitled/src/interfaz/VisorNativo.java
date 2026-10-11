import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Vistas de ASM y construcción de la misma instantánea, sin bloquear el hilo de Swing. */
final class VisorNativo extends JPanel {
    private final ProyectoNativo proyecto;
    private final Function<JFileChooser,Integer> seleccionarCarpeta;
    private final JTabbedPane vistas = new JTabbedPane();
    private final JComboBox<String> destino = new JComboBox<>(new String[]{"Linux x64", "Windows x64"});
    private final JComboBox<String> entrada;
    private final JButton generar = new JButton("Generar ASM"), exportar = new JButton("Exportar ASM…"),
            exportarIr = new JButton("Exportar IR destino…"), ensamblar = new JButton("Ensamblar…"), enlazar = new JButton("Construir ejecutable…"),
            ejecutar = new JButton("Ejecutar"), cancelar = new JButton("Cancelar"), herramientas = new JButton("Herramientas…"),
            enviar = new JButton("Enviar"), cerrarEntrada = new JButton("Cerrar entrada");
    private final JCheckBox optimizar = new JCheckBox("Optimizar H7"), interactiva = new JCheckBox("Entrada interactiva",true);
    private final JLabel estado = new JLabel(), plataforma = new JLabel();
    private final JPanel cuadruplosUsados = new JPanel(new BorderLayout());
    private final JTextArea originalIr = texto(), originalTipada = texto(), optimizadaTipada = texto(), informeOpt = texto(), irDestino = texto(), asm = texto(), cuerpos = texto(), soporte = texto(), adaptador = texto(),
            log = texto(), stdout = texto(), stderr = texto(), stdin = new JTextArea(4, 30);
    private final JSpinner plazoEjecucion = new JSpinner(new SpinnerNumberModel(300, 1, 3600, 1));
    private final JTabbedPane canales = new JTabbedPane();
    private final Map<DestinoNativoIr,ConstructorNativo.Herramientas> configuraciones = new EnumMap<>(DestinoNativoIr.class);
    private ProyectoNativo.Preparado preparado;
    private ConstructorNativo.Artefacto artefacto;
    private SwingWorker<?,?> trabajador;
    private ProcesoNativo.EntradaInteractiva entradaActiva;
    private ProcesoNativo.Control control;
    private long version;
    private boolean desactualizado, ocupado, objetoListo, enlaceListo;
    private String ejecucion = "pendiente", fase = "";
    private Runnable dependenciaDesactualizada = () -> {};

    VisorNativo(ProyectoNativo proyecto) { this(proyecto,null); }
    VisorNativo(ProyectoNativo proyecto, Function<JFileChooser,Integer> selector) {
        super(new BorderLayout(0, 8)); this.proyecto = proyecto;
        seleccionarCarpeta = selector == null ? s -> s.showOpenDialog(this) : selector;
        setBorder(new EmptyBorder(10,10,10,10));
        entrada = new JComboBox<>(proyecto.entradas.toArray(new String[0]));
        if (proyecto.ir.modulo != null && proyecto.ir.modulo.entrada != null) entrada.setSelectedItem(proyecto.ir.modulo.entrada.propietaria);
        if (ConstructorNativo.hostWindows()) destino.setSelectedIndex(1);
        JPanel barras = new JPanel(new GridLayout(0,1,0,6));
        JPanel seleccion = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        seleccion.add(new JLabel("Destino:")); seleccion.add(destino); seleccion.add(new JLabel("Entrada:")); seleccion.add(entrada);
        seleccion.add(optimizar); seleccion.add(generar); seleccion.add(exportar); seleccion.add(herramientas); barras.add(seleccion);
        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        acciones.add(exportarIr); acciones.add(ensamblar); acciones.add(enlazar); acciones.add(ejecutar); acciones.add(cancelar); barras.add(acciones);
        barras.add(estado); barras.add(plataforma); add(barras,BorderLayout.NORTH);
        vistas.addTab("ASM completo",new JScrollPane(asm));
        vistas.addTab("IR física del destino",new JScrollPane(irDestino)); vistas.addTab("Cuerpos por cuádruplo",new JScrollPane(cuerpos));
        vistas.addTab("Datos y puentes al runtime",new JScrollPane(soporte)); vistas.addTab("Adaptador de plataforma",new JScrollPane(adaptador));
        vistas.addTab("Construcción",new JScrollPane(log));
        JPanel consola = new JPanel(new BorderLayout(0,6));
        JPanel captura = new JPanel(new BorderLayout(0,4));
        JPanel titulo = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        titulo.add(interactiva); titulo.add(new JLabel("Plazo total de ejecución (s):")); titulo.add(plazoEjecucion);
        interactiva.setToolTipText("Activada: responde durante la ejecución. Desactivada: envía todo el texto inicial y cierra la entrada.");
        JPanel envio = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        envio.add(enviar); envio.add(cerrarEntrada); envio.add(new JLabel("Escribe tu respuesta y pulsa Enviar o Ctrl+Enter."));
        captura.add(titulo,BorderLayout.NORTH); captura.add(new JScrollPane(stdin),BorderLayout.CENTER); captura.add(envio,BorderLayout.SOUTH);
        consola.add(captura,BorderLayout.NORTH);
        canales.addTab("Salida estándar",new JScrollPane(stdout)); canales.addTab("Errores",new JScrollPane(stderr));
        consola.add(canales,BorderLayout.CENTER); vistas.addTab("Consola",consola);
        JTabbedPane comparacion=new JTabbedPane();
        comparacion.addTab("IR tipada original",new JScrollPane(originalTipada)); comparacion.addTab("IR tipada usada",new JScrollPane(optimizadaTipada));
        comparacion.addTab("Cuádruplos originales",new JScrollPane(originalIr)); comparacion.addTab("Cuádruplos didácticos usados",cuadruplosUsados); comparacion.addTab("Resumen",new JScrollPane(informeOpt));
        vistas.addTab("Comparar optimización",comparacion); add(vistas,BorderLayout.CENTER);
        optimizar.addActionListener(e -> cambiarSeleccion());
        generar.addActionListener(e -> generar()); destino.addActionListener(e -> cambiarSeleccion()); entrada.addActionListener(e -> cambiarSeleccion());
        exportar.addActionListener(e -> exportar(false)); exportarIr.addActionListener(e -> exportar(true)); ensamblar.addActionListener(e -> construir(ConstructorNativo.Etapa.OBJETO));
        enlazar.addActionListener(e -> construir(ConstructorNativo.Etapa.EJECUTABLE)); ejecutar.addActionListener(e -> ejecutar());
        cancelar.addActionListener(e -> cancelar()); herramientas.addActionListener(e -> configurar());
        enviar.addActionListener(e -> enviarEntrada()); cerrarEntrada.addActionListener(e -> cerrarEntrada());
        stdin.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"),"enviar");
        stdin.getActionMap().put("enviar",new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { enviarEntrada(); }
        });
        EstiloInterfaz.aplicar(this);
        actualizar(); if (proyecto.ir.esExitoso()) generar(); else asm.setText(proyecto.ir.diagnosticos);
    }
    private static JTextArea texto() {
        JTextArea t = new JTextArea(); t.setEditable(false); t.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13)); return t;
    }
    private DestinoNativoIr perfil() { return destino.getSelectedIndex() == 0 ? DestinoNativoIr.LINUX_X64 : DestinoNativoIr.WINDOWS_X64; }
    private boolean vigente() { return !desactualizado && proyecto.ir.esExitoso(); }
    private void cambiarSeleccion() {
        detener(); preparado = null; artefacto = null; objetoListo = enlaceListo = false; ejecucion = "pendiente";
        for (JTextArea t : Arrays.asList(originalIr,originalTipada,optimizadaTipada,informeOpt,irDestino,asm,cuerpos,soporte,adaptador,stdout,stderr)) t.setText("");
        cuadruplosUsados.removeAll(); cuadruplosUsados.revalidate();cuadruplosUsados.repaint();
        fase = ""; actualizar(); if (vigente()) generar();
    }
    void marcarDesactualizado() {
        detener(); desactualizado = true; artefacto = null; preparado = null;
        objetoListo = enlaceListo = false; ejecucion = "pendiente"; fase = "Vuelve a analizar: cambió el proyecto."; actualizar();
    }
    private void detener() {
        version++; if (control != null) control.cancelar(); if (trabajador != null) trabajador.cancel(true);
        if (entradaActiva != null) entradaActiva.terminar(); entradaActiva = null;
        trabajador = null; ocupado = false;
    }
    void cancelar() {
        detener(); if (artefacto == null) objetoListo = enlaceListo = false;
        if (ejecucion.equals("en curso")) ejecucion = "cancelada";
        fase = "Operación cancelada"; actualizar();
    }
    private interface Trabajo<T> { T hacer(ProcesoNativo.Control c, Consumer<String> progreso) throws Exception; }
    private interface TrabajoConSalida<T> {
        T hacer(ProcesoNativo.Control c, Consumer<String> progreso, Consumer<String> salida, Consumer<String> errores) throws Exception;
    }
    private static final class Evento {
        final int canal; final String texto;
        Evento(int canal,String texto) { this.canal=canal;this.texto=texto; }
    }
    private <T> void lanzar(String nombre, Trabajo<T> trabajo, Consumer<T> aceptar) {
        lanzarConSalida(nombre,(c,p,out,err) -> trabajo.hacer(c,p),aceptar);
    }
    private <T> void lanzarConSalida(String nombre, TrabajoConSalida<T> trabajo, Consumer<T> aceptar) {
        detener(); ocupado = true; fase = nombre;
        final long ticket = version; final ProcesoNativo.Control c = new ProcesoNativo.Control(); control = c;
        trabajador = new SwingWorker<T,Evento>() {
            protected T doInBackground() throws Exception {
                return trabajo.hacer(c,s -> publish(new Evento(0,s)),s -> publish(new Evento(1,s)),s -> publish(new Evento(2,s)));
            }
            protected void process(java.util.List<Evento> mensajes) {
                if (ticket != version || desactualizado) return;
                for (Evento evento : mensajes) {
                    if (evento.canal != 0) {
                        // done() fija la captura completa: un callback tardío nunca la duplica.
                        if (!ocupado || !ejecucion.equals("en curso")) continue;
                        JTextArea t = evento.canal == 1 ? stdout : stderr;
                        t.append(evento.texto); t.setCaretPosition(t.getDocument().getLength()); continue;
                    }
                    String m = evento.texto; anotar(m);
                    if (m.equals("Ensamblado")) objetoListo = true;
                    if (m.equals("Enlazado")) enlaceListo = true;
                    if (!m.startsWith("$") && !m.contains("\n") && m.length() < 60) fase = m;
                }
                actualizar();
            }
            protected void done() {
                if (ticket != version || desactualizado) return;
                ocupado = false; trabajador = null;
                try {
                    T resultado = get(); aceptar.accept(resultado);
                } catch (CancellationException e) { fase = "Operación cancelada"; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); fase = "Operación cancelada"; }
                catch (ExecutionException e) {
                    String detalle = String.valueOf(e.getCause().getMessage());
                    fase = nombre.equals("Ejecución") ? "Falló la ejecución: " + detalle.split("\n",2)[0]
                            : "Falló " + nombre + ". Consulta el diagnóstico en Construcción.";
                    anotar(detalle);
                    if (!nombre.equals("Ejecución")) vistas.setSelectedIndex(vistas.indexOfTab("Construcción"));
                    if (artefacto == null) objetoListo = enlaceListo = false;
                    if (ejecucion.equals("en curso")) ejecucion = "no completada";
                    for (Throwable causa = e.getCause(); causa != null; causa = causa.getCause()) {
                        if (causa instanceof ProyectoNativo.DependenciaDesactualizada) { marcarDesactualizado(); dependenciaDesactualizada.run(); }
                        if (nombre.equals("Ejecución") && causa instanceof ProcesoNativo.Fallo) {
                            ProcesoNativo.Resultado parcial = ((ProcesoNativo.Fallo)causa).parcial;
                            stdout.setText(parcial.stdout()); stderr.setText(parcial.stderr());
                        }
                    }
                }
                if (nombre.equals("Ejecución")) entradaActiva = null;
                actualizar();
            }
        };
        actualizar(); trabajador.execute();
    }
    private void anotar(String s) {
        log.append(s + "\n"); if (log.getDocument().getLength() > ProcesoNativo.LIMITE_SALIDA) log.replaceRange("",0,log.getDocument().getLength()-ProcesoNativo.LIMITE_SALIDA);
        log.setCaretPosition(log.getDocument().getLength());
    }
    void generar() {
        if (!vigente()) return;
        DestinoNativoIr d = perfil(); String main = (String)entrada.getSelectedItem(); boolean aplicar=optimizar.isSelected();
        preparado = null; artefacto = null; objetoListo = enlaceListo = false; ejecucion = "pendiente";
        lanzar("Generación ASM", (c,p) -> { c.comprobar(); ProyectoNativo.Preparado r = proyecto.generar(d,main,aplicar); c.comprobar(); return r; }, r -> {
            preparado = r; originalIr.setText(r.atomicaOriginal);originalTipada.setText(r.tipadaOriginal);optimizadaTipada.setText(r.tipada);
            informeOpt.setText(r.resumenOptimizacion);
            cuadruplosUsados.removeAll();cuadruplosUsados.add(TablaDidacticaIr.panel(r.didactica,false),BorderLayout.CENTER);
            EstiloInterfaz.aplicar(cuadruplosUsados);cuadruplosUsados.revalidate();cuadruplosUsados.repaint();
            for(JTextArea t:Arrays.asList(originalIr,originalTipada,optimizadaTipada,informeOpt))t.setCaretPosition(0);
            irDestino.setText(r.atomica); irDestino.setCaretPosition(0); asm.setText(r.asm); asm.setCaretPosition(0);
            mostrarSecciones(r.asm); fase = r.optimizado?"ASM generado · Optimización H7 aplicada":"ASM generado desde IR verificada";
        });
    }
    private void mostrarSecciones(String completo) {
        StringBuilder cuerpo = new StringBuilder(), datos = new StringBuilder(), abi = new StringBuilder();
        int seccion = 0;
        for (String linea : completo.split("\n",-1)) {
            if (linea.startsWith("; SOPORTE:")) seccion = 0;
            else if (linea.startsWith("; ADAPTADOR:")) seccion = 1;
            else if (linea.startsWith("; IR #")) seccion = 2;
            else if (seccion == 2 && linea.startsWith("abi_") && linea.endsWith("_fin:")) seccion = 1;
            (seccion == 0 ? datos : seccion == 1 ? abi : cuerpo).append(linea).append('\n');
        }
        cuerpos.setText("; Vista parcial de las instrucciones asociadas a cuádruplos.\n" + cuerpo);
        soporte.setText("; Vista parcial de datos y puentes.\n" + datos);
        adaptador.setText("; Vista parcial de prólogos, entrada y metadatos de plataforma.\n" + abi);
        for (JTextArea t : Arrays.asList(cuerpos,soporte,adaptador)) t.setCaretPosition(0);
    }
    private void exportar(boolean ir) {
        if (!vigente() || ocupado || preparado == null) return;
        JFileChooser s = new JFileChooser(); s.setDialogTitle(ir ? "Exportar IR física del destino" : "Exportar ASM completo de la instantánea"); s.setSelectedFile(new java.io.File("Programa-" + perfil().id + (ir ? ".ir" : ".asm")));
        if (s.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path archivo = s.getSelectedFile().toPath();
        try {
            proyecto.ir.validarDestino(archivo);
            if (Files.exists(archivo) && JOptionPane.showConfirmDialog(this,"¿Reemplazar el archivo existente?","Exportar ASM",JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        } catch (IOException | IllegalArgumentException e) { anotar(e.getMessage()); return; }
        ProyectoNativo.Preparado p = preparado;
        lanzar(ir ? "Exportación IR" : "Exportación ASM", (c,log) -> { c.comprobar(); p.exportar(archivo,ir,c); return archivo; }, r -> { fase = ir ? "IR exportada" : "ASM exportado"; anotar(r.toString()); });
    }
    private void construir(ConstructorNativo.Etapa etapa) {
        if (!vigente() || ocupado || preparado == null) return;
        JFileChooser s = new JFileChooser();
        s.setDialogTitle("Elegir carpeta donde guardar los artefactos");
        s.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); s.setMultiSelectionEnabled(false);
        s.setApproveButtonText("Guardar aquí");
        Path inicial = proyecto.principal.getParent();
        if (inicial != null && Files.isDirectory(inicial)) s.setCurrentDirectory(inicial.toFile());
        if (seleccionarCarpeta.apply(s) != JFileChooser.APPROVE_OPTION) return;
        if (s.getSelectedFile() == null) return;
        // La carpeta elegida es el contenedor. El builder publica una subcarpeta nueva.
        construirDentroDe(s.getSelectedFile().toPath(),etapa);
    }
    private void construirDentroDe(Path carpeta, ConstructorNativo.Etapa etapa) {
        iniciarConstruccion(carpeta,etapa,true);
    }
    void construirEn(Path carpeta, ConstructorNativo.Etapa etapa) {
        iniciarConstruccion(carpeta,etapa,false);
    }
    private void iniciarConstruccion(Path carpeta, ConstructorNativo.Etapa etapa, boolean dentro) {
        if (!vigente() || ocupado || preparado == null) return;
        ProyectoNativo.Preparado p = preparado; ConstructorNativo.Herramientas h = configuracion();
        artefacto = null; objetoListo = enlaceListo = false; ejecucion = "pendiente"; log.setText(""); stdout.setText(""); stderr.setText("");
        vistas.setSelectedIndex(vistas.indexOfTab("Construcción"));
        lanzar("Construcción", (c,log) -> {
            Path salida = carpeta.toAbsolutePath().normalize();
            if (dentro) {
                if (!Files.isDirectory(salida)) throw new IOException("La carpeta seleccionada no existe o no es un directorio: " + salida);
                String nombre = "Programa-" + p.destino.id + "-" + System.currentTimeMillis();
                Path padre = salida; salida = padre.resolve(nombre);
                for (int n = 1; Files.exists(salida,LinkOption.NOFOLLOW_LINKS); n++) salida = padre.resolve(nombre + "-" + n);
            }
            log.accept("Carpeta de resultados: " + salida);
            return ConstructorNativo.construir(p,salida,etapa,h,c,log);
        }, a -> {
            artefacto = a; objetoListo = true; enlaceListo = a.ejecutable != null;
            fase = "Artefactos guardados en " + a.carpeta; anotar(a.carpeta.toString());
        });
    }
    void ejecutar() {
        if (!vigente() || ocupado || artefacto == null || artefacto.ejecutable == null || !ConstructorNativo.puedeEjecutar(perfil())) return;
        ConstructorNativo.Artefacto a = artefacto; String texto = stdin.getText(); int plazo = (Integer)plazoEjecucion.getValue();
        ProcesoNativo.EntradaInteractiva nueva = interactiva.isSelected() ? new ProcesoNativo.EntradaInteractiva() : null;
        if (nueva != null && !texto.isEmpty()) {
            try { nueva.enviar(conSaltoFinal(texto)); }
            catch (IOException e) { fase = e.getMessage(); anotar(fase); actualizar(); return; }
        }
        stdout.setText(""); stderr.setText(""); ejecucion = "en curso";
        vistas.setSelectedIndex(vistas.indexOfTab("Consola")); canales.setSelectedIndex(0);
        lanzarConSalida("Ejecución", (c,p,out,err) -> nueva == null
                ? ConstructorNativo.ejecutarPrograma(a,texto,plazo,c)
                : ConstructorNativo.ejecutarProgramaInteractivo(a,nueva,plazo,c,out,err), r -> {
            stdout.setText(r.stdout()); stderr.setText(r.stderr()); stdout.setCaretPosition(0); stderr.setCaretPosition(0);
            if (!r.stderr().isEmpty()) canales.setSelectedIndex(1);
            ejecucion = "código " + r.codigo; fase = "Ejecución terminada";
            anotar("Proceso terminado con código " + r.codigo + ". Consulta los canales de consola.");
        });
        entradaActiva = nueva;
        if (nueva != null) { stdin.setText(""); stdin.requestFocusInWindow(); }
        actualizar();
    }
    private static String conSaltoFinal(String texto) { return texto.endsWith("\n") ? texto : texto + "\n"; }
    void enviarEntrada() {
        if (!ocupado || !ejecucion.equals("en curso") || entradaActiva == null || !entradaActiva.estaAbierta()) return;
        try {
            entradaActiva.enviar(conSaltoFinal(stdin.getText())); stdin.setText(""); stdin.requestFocusInWindow();
        } catch (IOException e) { fase = e.getMessage(); anotar(fase); }
        actualizar();
    }
    void cerrarEntrada() {
        if (!ocupado || !ejecucion.equals("en curso") || entradaActiva == null) return;
        entradaActiva.cerrar(); fase = "Entrada cerrada; esperando la terminación del programa"; actualizar();
    }
    private ConstructorNativo.Herramientas configuracion() { return configuraciones.computeIfAbsent(perfil(),ConstructorNativo.Herramientas::predeterminadas); }
    private void configurar() {
        ConstructorNativo.Herramientas h = configuracion(); JTextField n = new JTextField(h.nasm), cc = new JTextField(h.cc), ar = new JTextField(h.ar);
        JSpinner segundos = new JSpinner(new SpinnerNumberModel(h.plazo,1,3600,1));
        JPanel panel = new JPanel(new GridLayout(0,2,8,8)); panel.add(new JLabel("NASM")); panel.add(n);
        panel.add(new JLabel("Compilador C (Windows: MinGW UCRT)")); panel.add(cc); panel.add(new JLabel("Archivador ar")); panel.add(ar);
        panel.add(new JLabel("Plazo por herramienta (s)")); panel.add(segundos);
        if (JOptionPane.showConfirmDialog(this,panel,"Herramientas para " + perfil().id,JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try { configuraciones.put(perfil(),new ConstructorNativo.Herramientas(n.getText(),cc.getText(),ar.getText(),(Integer)segundos.getValue())); }
            catch (IllegalArgumentException e) { anotar(e.getMessage()); }
        }
    }
    private void actualizar() {
        boolean valida = vigente(), disponible = valida && !ocupado && preparado != null;
        optimizar.setEnabled(valida); destino.setEnabled(valida); entrada.setEnabled(valida); generar.setEnabled(valida && !ocupado);
        exportar.setEnabled(disponible); exportarIr.setEnabled(disponible); ensamblar.setEnabled(disponible); enlazar.setEnabled(disponible);
        ejecutar.setEnabled(disponible && artefacto != null && artefacto.ejecutable != null && ConstructorNativo.puedeEjecutar(perfil()));
        cancelar.setEnabled(ocupado); herramientas.setEnabled(!ocupado);
        boolean puedeEnviar = ocupado && ejecucion.equals("en curso") && entradaActiva != null && entradaActiva.estaAbierta();
        enviar.setEnabled(puedeEnviar); cerrarEntrada.setEnabled(puedeEnviar);
        stdin.setEnabled(!ocupado || puedeEnviar); interactiva.setEnabled(!ocupado); plazoEjecucion.setEnabled(!ocupado);
        estado.setText("IR: " + (valida ? "verificada" : "no disponible") + " · ASM: " + (preparado == null ? "pendiente" : "generado")
                + " · Objeto: " + (objetoListo ? "ensamblado" : "pendiente") + " · Enlace: " + (enlaceListo ? "realizado" : "pendiente") + " · Ejecución: " + ejecucion);
        estado.setToolTipText(fase);
        String aviso = perfil() == DestinoNativoIr.WINDOWS_X64 ? "Windows: aceptación en equipo Windows pendiente. " : "";
        if (!ConstructorNativo.puedeEjecutar(perfil())) aviso += "La ejecución requiere un equipo del destino seleccionado. ";
        plataforma.setText(aviso + fase);
    }
    // Acceso de pruebas sin diálogos ni una ventana de nivel superior.
    void alCambiarDependencia(Runnable accion) { dependenciaDesactualizada = accion; }
    boolean estaOcupado() { return ocupado; }
    String estadoActual() { return estado.getText(); }
    String diagnosticos() { return log.getText(); }
    String ensambladorVisible() { return asm.getText(); }
    void entradaConsola(String texto) { stdin.setText(texto); }
    String salidaConsola() { return stdout.getText(); }
    String erroresConsola() { return stderr.getText(); }
    void usarEntradaInteractiva(boolean activa) { interactiva.setSelected(activa); }
    void seleccionarOptimizacion(boolean activa) { optimizar.setSelected(activa); cambiarSeleccion(); }
    void seleccionarDestino(DestinoNativoIr d) { destino.setSelectedIndex(d == DestinoNativoIr.LINUX_X64 ? 0 : 1); }
    void usarHerramientas(ConstructorNativo.Herramientas h) { configuraciones.put(perfil(),h); }
}
