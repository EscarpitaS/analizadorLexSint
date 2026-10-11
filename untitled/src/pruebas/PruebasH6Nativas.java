import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;

/** Integración con NASM/C reales; ejecutable del snapshot sin Python ni checkout. */
public final class PruebasH6Nativas {
    private static int total;
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Uso: NASM CC AR [CC_WINDOWS AR_WINDOWS]");
        Path dir = Files.createTempDirectory("h6 nativo ü-");
        Path fuente = dir.resolve("editor sin guardar.txt");
        Files.writeString(fuente,"{ class Main { public main() { } } }");
        String actual = "import runtime.Consola; { class Main { public main() { Consola c = new Consola(); int n = c.leerInt(); c.imprimirLinea(\"Árbol: \" + (n * 3)); } } }";
        List<Main.ResultadoAnalisis> unidades = Main.analizarProyecto(fuente,actual,false);
        ProyectoNativo proyecto = new ProyectoNativo(VisorIr.generar(unidades,fuente),unidades,fuente);
        boolean optimizar=Arrays.asList(args).contains("--optimizar");
        ProyectoNativo.Preparado p = proyecto.generar(DestinoNativoIr.LINUX_X64,"Main",optimizar);
        ConstructorNativo.Herramientas h = new ConstructorNativo.Herramientas(args[0],args[1],args[2],30);
        List<String> log = new ArrayList<>();
        ConstructorNativo.Artefacto a = ConstructorNativo.construir(p,dir.resolve("Linux programa"),ConstructorNativo.Etapa.EJECUTABLE,h,new ProcesoNativo.Control(),log::add);
        exigir(Files.isExecutable(a.ejecutable) && Files.isRegularFile(a.objeto),"No se publicaron artefactos");
        exigir(log.containsAll(Arrays.asList("Ensamblado","Enlazado")),"Etapas indistinguibles");
        exigir(Files.readString(a.carpeta.resolve("programa.asm")).equals(p.asm),"El build cambió el ASM");
        if(optimizar)exigir(Files.readString(a.carpeta.resolve("programa.original.ir")).equals(p.atomicaOriginal)
                && Files.readString(a.carpeta.resolve("programa.original.tipada.ir")).equals(p.tipadaOriginal)
                && Files.readString(a.carpeta.resolve("identidad-programa.txt")).contains("optimizacion=H7"),"Build optimizado perdió original/identidad");
        exigir(Files.readString(a.carpeta.resolve("identidad-programa.txt")).contains(ProyectoNativo.huella(actual)),"No se registró la fuente del editor");
        exigir(Files.isRegularFile(a.carpeta.resolve("runtime/licencias/openjdk11/LICENSE")),"Licencia runtime ausente");
        ProcesoNativo.Resultado ok = ConstructorNativo.ejecutarPrograma(a,"7\n",10,new ProcesoNativo.Control());
        exigir(ok.codigo == 0 && ok.stdout().equals("Árbol: 21\n") && ok.stderr().isEmpty(),"Oráculo inválido: " + ok.stdout()+ok.stderr());
        ProcesoNativo.Resultado mal = ConstructorNativo.ejecutarPrograma(a,"abc\n",10,new ProcesoNativo.Control());
        exigir(mal.codigo == 1 && !mal.stderr().isEmpty(),"Error RC-1 no capturado");
        exigir(Files.readString(fuente).contains("public main() { }"),"Se guardó el editor sobre la fuente");
        rechaza(() -> ConstructorNativo.construir(p,a.carpeta,ConstructorNativo.Etapa.OBJETO,h,new ProcesoNativo.Control(),s -> {}),"existe");
        ConstructorNativo.Artefacto o = ConstructorNativo.construir(p,dir.resolve("solo objeto"),ConstructorNativo.Etapa.OBJETO,h,new ProcesoNativo.Control(),s -> {});
        exigir(o.ejecutable == null && Files.isRegularFile(o.objeto),"Se fingió enlace al ensamblar");
        rechaza(() -> ConstructorNativo.ejecutarPrograma(o,"",2,new ProcesoNativo.Control()),"ejecutable");
        Path falla = dir.resolve("falla");
        rechaza(() -> ConstructorNativo.construir(p,falla,ConstructorNativo.Etapa.EJECUTABLE,new ConstructorNativo.Herramientas(args[0],"/no-existe/cc",args[2],10),new ProcesoNativo.Control(),s -> {}),"no-existe");
        exigir(!Files.exists(falla),"Falló enlace pero publicó carpeta");
        ProcesoNativo.Control cancelado = new ProcesoNativo.Control(); cancelado.cancelar();
        rechaza(() -> ConstructorNativo.construir(p,dir.resolve("cancelado"),ConstructorNativo.Etapa.OBJETO,h,cancelado,s -> {}),"cancelada");
        procesos(dir,args[1]);
        Path contenedor = Files.createDirectory(dir.resolve("carpeta elegida ü"));
        Path conservar = contenedor.resolve("fuente.txt"); Files.writeString(conservar,"No sobrescribir");
        boolean[] cancelarSelector = {false}; int[] selecciones = {0};
        AtomicReference<VisorNativo> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new VisorNativo(proyecto, selector -> {
            selecciones[0]++;
            exigir(selector.getFileSelectionMode()==JFileChooser.DIRECTORIES_ONLY
                    && selector.getApproveButtonText().equals("Guardar aquí"),"Selector no permite elegir el contenedor");
            selector.setSelectedFile(contenedor.toFile());
            return cancelarSelector[0] ? JFileChooser.CANCEL_OPTION : JFileChooser.APPROVE_OPTION;
        }))); PruebasH6.esperar(panel.get());
        if(optimizar) { SwingUtilities.invokeAndWait(() -> panel.get().seleccionarOptimizacion(true));PruebasH6.esperar(panel.get()); }
        SwingUtilities.invokeAndWait(() -> {
            panel.get().usarHerramientas(h); pulsar(panel.get(),"Ensamblar…");
            exigir(construccionVisible(panel.get()),"Ensamblar ocultó el progreso");
        });
        PruebasH6.esperar(panel.get());
        List<Path> ensamblados = resultados(contenedor);
        exigir(ensamblados.size()==1 && Files.isRegularFile(ensamblados.get(0).resolve("programa.o"))
                && !Files.exists(ensamblados.get(0).resolve("programa")),"Botón Ensamblar no publicó solo el objeto: "+panel.get().diagnosticos());
        SwingUtilities.invokeAndWait(() -> {
            exigir(panel.get().estadoActual().contains("Objeto: ensamblado") && panel.get().estadoActual().contains("Enlace: pendiente"),"Ensamblar indica enlace");
            pulsar(panel.get(),"Construir ejecutable…");
            exigir(construccionVisible(panel.get()),"Construir ejecutable ocultó el progreso");
        });
        PruebasH6.esperar(panel.get());
        List<Path> construidos = resultados(contenedor);
        exigir(construidos.size()==2 && construidos.stream().anyMatch(r -> Files.isExecutable(r.resolve("programa"))),"Botón Construir no publicó ejecutable: "+panel.get().diagnosticos());
        exigir(Files.readString(conservar).equals("No sobrescribir") && Files.isRegularFile(ensamblados.get(0).resolve("programa.o")),"Se sobrescribieron fuentes/resultados previos");
        SwingUtilities.invokeAndWait(() -> {
            exigir(panel.get().estadoActual().contains("Enlace: realizado") && panel.get().estadoActual().contains("Ejecución: pendiente"),"GUI confunde enlace/ejecución: " + panel.get().estadoActual()+panel.get().diagnosticos());
            panel.get().entradaConsola("8\n"); panel.get().ejecutar();
        });
        PruebasH6.esperar(panel.get());
        SwingUtilities.invokeAndWait(() -> {
            exigir(panel.get().salidaConsola().equals("Árbol: 24\n") && panel.get().erroresConsola().isEmpty()
                    && panel.get().estadoActual().contains("Ejecución: código 0"),"GUI no muestra consola/código de retorno");
            panel.get().entradaConsola("abc\n"); panel.get().ejecutar();
        });
        PruebasH6.esperar(panel.get());
        SwingUtilities.invokeAndWait(() -> {
            exigir(panel.get().estadoActual().contains("Ejecución: código 1") && !panel.get().erroresConsola().isEmpty(),"GUI ocultó el error de ejecución");
        });
        SwingUtilities.invokeAndWait(() -> {
            cancelarSelector[0]=true; pulsar(panel.get(),"Construir ejecutable…");
            exigir(!panel.get().estaOcupado() && panel.get().estadoActual().contains("Enlace: realizado"),"Cancelar selector invalidó el ejecutable");
            cancelarSelector[0]=false;
            panel.get().usarHerramientas(new ConstructorNativo.Herramientas("/no-existe/nasm",args[1],args[2],10));
            pulsar(panel.get(),"Ensamblar…");
        });
        PruebasH6.esperar(panel.get());
        SwingUtilities.invokeAndWait(() -> {
            exigir(construccionVisible(panel.get()) && panel.get().diagnosticos().contains("/no-existe/nasm")
                    && panel.get().estadoActual().contains("Objeto: pendiente"),"GUI ocultó el fallo de NASM");
            panel.get().marcarDesactualizado();
            exigir(panel.get().estadoActual().contains("Enlace: pendiente"),"GUI retuvo ejecutable stale");
        });
        exigir(selecciones[0]==4 && resultados(contenedor).size()==2 && Files.readString(conservar).equals("No sobrescribir"),"Selector/fallo publicó o alteró resultados");
        Path interrumpido = dir.resolve("objeto interrumpido"); ProcesoNativo.Control cortado = new ProcesoNativo.Control();
        rechaza(() -> ConstructorNativo.construir(p,interrumpido,ConstructorNativo.Etapa.OBJETO,h,cortado,
            mensaje -> { if (mensaje.equals("Ensamblado")) cortado.cancelar(); }),"cancelada");
        exigir(!Files.exists(interrumpido),"Publicó artefacto después de cancelar");
        if (args.length == 5) {
            ProyectoNativo.Preparado w = proyecto.generar(DestinoNativoIr.WINDOWS_X64,"Main");
            ConstructorNativo.Artefacto exe = ConstructorNativo.construir(w,dir.resolve("Windows programa"),ConstructorNativo.Etapa.EJECUTABLE,new ConstructorNativo.Herramientas(args[0],args[3],args[4],30),new ProcesoNativo.Control(),s -> {});
            byte[] bytes = Files.readAllBytes(exe.ejecutable);
            exigir(bytes[0]=='M' && bytes[1]=='Z',"Salida Windows no PE");
            exigir(Files.readString(exe.carpeta.resolve("runtime/identidad-runtime.txt")).contains("UCRT"),"No comprobó UCRT");
            if (!ConstructorNativo.hostWindows()) rechaza(() -> ConstructorNativo.ejecutarPrograma(exe,"7\n",2,new ProcesoNativo.Control()),"anfitrión");
        }
        PruebasConsolaInteractiva.ejecutar(dir,h,optimizar);
        System.out.println("H6 nativo: " + total + " comprobaciones OK; artefactos: " + dir);
    }
    private static void pulsar(Container c,String texto) {
        JButton b=buscarBoton(c,texto);
        exigir(b!=null && b.isEnabled(),"Botón deshabilitado: "+texto); b.doClick();
    }
    private static JButton buscarBoton(Container c,String texto) {
        for(Component h:c.getComponents()) {
            if(h instanceof JButton && ((JButton)h).getText().equals(texto))return (JButton)h;
            if(h instanceof Container) { JButton b=buscarBoton((Container)h,texto);if(b!=null)return b; }
        }
        return null;
    }
    private static boolean construccionVisible(Container c) {
        for(Component h:c.getComponents()) {
            if(h instanceof JTabbedPane) {
                JTabbedPane t=(JTabbedPane)h;
                if(t.indexOfTab("Construcción")>=0)return t.getSelectedIndex()==t.indexOfTab("Construcción");
            }
            if(h instanceof Container && construccionVisible((Container)h))return true;
        }
        return false;
    }
    private static List<Path> resultados(Path contenedor) throws Exception {
        try(java.util.stream.Stream<Path> rutas=Files.list(contenedor)) {
            return rutas.filter(p -> p.getFileName().toString().startsWith("Programa-linux-x64-")).collect(java.util.stream.Collectors.toList());
        }
    }
    private static void procesos(Path dir,String cc) throws Exception {
        Path c = dir.resolve("canales.c"), exe = dir.resolve("canales");
        Files.writeString(c,"#include <stdio.h>\n#include <unistd.h>\n#include <string.h>\nint main(int n,char **a){ if(n>1 && strcmp(a[1],\"dormir\")==0){fputs(\"parcial\",stdout);fflush(stdout);sleep(20);return 0;} if(n>1 && strcmp(a[1],\"exceso\")==0){for(int i=0;i<2000000;i++)putchar('X');return 0;} for(int i=0;i<20000;i++){fputs(\"salida\\n\",stdout);fputs(\"errores\\n\",stderr);} return 3;}\n");
        ProcesoNativo.Resultado compilado = ProcesoNativo.ejecutar(Arrays.asList(cc,c.toString(),"-o",exe.toString()),dir,new byte[0],10,new ProcesoNativo.Control()); exigir(compilado.codigo==0,compilado.stderr());
        ProcesoNativo.Resultado r = ProcesoNativo.ejecutar(Collections.singletonList(exe.toString()),dir,new byte[2_000_000],5,new ProcesoNativo.Control());
        exigir(r.codigo==3 && r.stdout().lines().count()==20000 && r.stderr().lines().count()==20000,"Pipes bloqueados o mezclados");
        try { ProcesoNativo.ejecutar(Arrays.asList(exe.toString(),"dormir"),dir,new byte[0],1,new ProcesoNativo.Control()); throw new AssertionError("No aplicó plazo"); }
        catch (ProcesoNativo.Fallo fallo) { exigir(fallo.getMessage().contains("límite") && fallo.parcial.stdout().equals("parcial"),"Se perdió stdout al exceder el plazo"); }
        rechaza(() -> ProcesoNativo.ejecutar(Arrays.asList(exe.toString(),"exceso"),dir,new byte[0],5,new ProcesoNativo.Control()),"límite");
        ProcesoNativo.Control control = new ProcesoNativo.Control();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t = new Thread(() -> { try { ProcesoNativo.ejecutar(Arrays.asList(exe.toString(),"dormir"),dir,new byte[0],10,control); } catch (Throwable e) { error.set(e); } });
        t.start(); Thread.sleep(150); control.cancelar(); t.join(3000);
        exigir(!t.isAlive() && error.get()!=null && error.get().getMessage().contains("cancelada"),"Cancelación no detuvo el proceso");
    }
    private interface Accion { void ejecutar() throws Exception; }
    private static void rechaza(Accion a,String mensaje) throws Exception {
        try { a.ejecutar(); throw new AssertionError("Debió rechazar " + mensaje); }
        catch (java.io.IOException | IllegalArgumentException e) { exigir(e.getMessage().contains(mensaje),"Diagnóstico incorrecto: " + e); }
    }
    private static void exigir(boolean c,String m) { total++; if(!c)throw new AssertionError(m); }
}
