import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Pruebas de instantáneas, selecciones, invalidación y GUI sin herramientas externas. */
public final class PruebasH6 {
    private static int comprobaciones;
    public static void main(String[] args) throws Exception { ejecutar(); }
    static void ejecutar() {
        try {
            Path dir = Files.createTempDirectory("h6-vista-");
            Path principal = dir.resolve("Main.txt");
            Files.writeString(principal,"{ class Main { public main() { int anterior = 2; } } }");
            String actual = "import runtime.Consola; { class Main { public main() { Consola c = new Consola(); c.imprimirLinea(7654321); } } class Otra { public main() { } } }";
            List<Main.ResultadoAnalisis> unidades = Main.analizarProyecto(principal,actual,false);
            VisorIr.Resultado ir = VisorIr.generar(unidades,principal);
            exigir(ir.esExitoso(),ir.diagnosticos);
            ProyectoNativo proyecto = new ProyectoNativo(ir,unidades,principal);
            exigir(proyecto.entradas.containsAll(Arrays.asList("Main","Otra")),"Faltan entradas explícitas");
            ProyectoNativo.Preparado linux = proyecto.generar(DestinoNativoIr.LINUX_X64,"Main");
            ProyectoNativo.Preparado windows = proyecto.generar(DestinoNativoIr.WINDOWS_X64,"Otra");
            exigir(linux.atomica.contains("7654321") && windows.asm.contains("Microsoft x64"),"Destino o instantánea incorrectos");
            exigir(windows.entrada.equals("Otra") && windows.identidad().contains("entrada=Otra"),"Se ignoró la entrada elegida");
            exigir(linux.asm.equals(new GeneradorEnsamblador().generar(new LinealizadorIr().transformar(ir.modulo))),"El visor cambió el backend");
            Path exportado = dir.resolve("salida.asm"); linux.exportar(exportado);
            exigir(Files.readString(exportado).equals(linux.asm),"Exportación incompleta");
            rechaza(() -> linux.exportar(principal),"fuente");
            Path alias = dir.resolve("alias.asm"); Files.createSymbolicLink(alias,principal); rechaza(() -> linux.exportar(alias),"fuente");
            exigir(Files.readString(principal).contains("anterior"),"Se guardó el editor sobre el disco");
            Path nuevo = dir.resolve("Sin_titulo.txt");
            List<Main.ResultadoAnalisis> sinGuardar = Main.analizarProyecto(nuevo,actual,false);
            exigir(new ProyectoNativo(VisorIr.generar(sinGuardar,nuevo),sinGuardar,nuevo).generar(DestinoNativoIr.LINUX_X64,"Main").atomica.contains("7654321") && !Files.exists(nuevo),"Documento nuevo no soportado");
            AtomicReference<VisorNativo> vista = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JTabbedPane tabs = new JTabbedPane();
                AnalizadorLexicoUI.llenarPestanas(tabs,unidades,u -> new JPanel(),ir,proyecto);
                exigir(tabs.getTabCount() == unidades.size()+2 && tabs.getTitleAt(unidades.size()+1).equals("ASM y ejecutable"),"Pestaña nativa no integrada");
                vista.set((VisorNativo)tabs.getComponentAt(unidades.size()+1));
            });
            esperar(vista.get());
            SwingUtilities.invokeAndWait(() -> {
                exigir(vista.get().ensambladorVisible().contains("System V AMD64"),"ASM no visible");
                exigir(vista.get().estadoActual().contains("ASM: generado") && vista.get().estadoActual().contains("Enlace: pendiente"),"Se confundió generación con enlace");
                vista.get().seleccionarDestino(DestinoNativoIr.WINDOWS_X64);
            });
            esperar(vista.get());
            SwingUtilities.invokeAndWait(() -> {
                exigir(vista.get().ensambladorVisible().contains("Microsoft x64"),"No se regeneró destino");
                vista.get().generar(); vista.get().marcarDesactualizado();
            });
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> {
                exigir(!vista.get().estaOcupado() && vista.get().estadoActual().contains("ASM: pendiente"),"Resultado tardío aceptado");
                for (JButton b : botones(vista.get())) if (Arrays.asList("Generar ASM","Exportar IR destino…","Exportar ASM…","Ensamblar…","Construir ejecutable…","Ejecutar","Cancelar").contains(b.getText())) exigir(!b.isEnabled(),"Acción stale habilitada: " + b.getText());
            });
            List<Main.ResultadoAnalisis> invalido = Main.analizarProyecto(principal,"{ class Main { public main() { int x = true; } } }",false);
            ProyectoNativo fallido = new ProyectoNativo(VisorIr.generar(invalido,principal),invalido,principal);
            rechaza(() -> fallido.generar(DestinoNativoIr.LINUX_X64,"Main"),"IR");
            Files.writeString(dir.resolve("Aux.txt"),"{ class Aux { public void f() { } public main() { } } }");
            String conImport = "import Aux; { class Main { public main() { Aux a = new Aux(); a.f(); } } }";
            List<Main.ResultadoAnalisis> imports = Main.analizarProyecto(principal,conImport,true);
            ProyectoNativo importado = new ProyectoNativo(VisorIr.generar(imports,principal),imports,principal);
            ProyectoNativo.Preparado anterior = importado.generar(DestinoNativoIr.LINUX_X64,"Main");
            Files.writeString(dir.resolve("Aux.txt"),"{ class Aux { public main() { } } }");
            rechaza(importado::comprobarDependencias,"dependencia"); rechaza(() -> anterior.exportar(exportado),"dependencia");
            exigir(Files.readString(exportado).equals(linux.asm),"Se exportó una dependencia stale");
            AtomicReference<VisorNativo> importVista = new AtomicReference<>();
            boolean[] notificado = {false};
            SwingUtilities.invokeAndWait(() -> {
                importVista.set(new VisorNativo(importado));
                importVista.get().alCambiarDependencia(() -> notificado[0] = true);
            });
            esperar(importVista.get());
            SwingUtilities.invokeAndWait(() -> exigir(notificado[0] && importVista.get().estadoActual().contains("IR: no disponible"),"No invalidó las vistas al cambiar un import"));
            SwingUtilities.invokeAndWait(() -> vista.get().cancelar());
            System.out.println("H6 GUI: " + comprobaciones + " comprobaciones OK");
        } catch (Exception e) { throw new AssertionError("H6: " + e,e); }
    }
    private static List<JButton> botones(Container c) {
        List<JButton> r = new ArrayList<>(); for (Component h : c.getComponents()) {
            if (h instanceof JButton) r.add((JButton)h);
            if (h instanceof Container) r.addAll(botones((Container)h));
        } return r;
    }
    static void esperar(VisorNativo v) throws Exception {
        long fin = System.nanoTime()+10_000_000_000L;
        while (true) {
            boolean[] ocupado = {false}; SwingUtilities.invokeAndWait(() -> ocupado[0]=v.estaOcupado());
            if (!ocupado[0]) return;
            if (System.nanoTime()>fin) throw new AssertionError("Trabajador GUI bloqueado"); Thread.sleep(25);
        }
    }
    private interface Accion { void ejecutar() throws Exception; }
    private static void rechaza(Accion a,String mensaje) throws Exception {
        try { a.ejecutar(); throw new AssertionError("Debió rechazar " + mensaje); }
        catch (java.io.IOException | IllegalArgumentException e) { exigir(e.getMessage().contains(mensaje),"Diagnóstico incorrecto: " + e); }
    }
    static void exigir(boolean condicion,String mensaje) { comprobaciones++; if (!condicion) throw new AssertionError(mensaje); }
}
