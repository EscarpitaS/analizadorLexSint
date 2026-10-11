import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Entrada por turnos y salida en vivo con procesos reales y botones Swing. */
public final class PruebasConsolaInteractiva {
    private static int total;
    private static final String NOMBRE = "Zoë 🌵";
    private static final String ESPERADO = "Nombre: Edad: Hola, " + NOMBRE + " (27)\n";
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Uso: NASM CC AR [--optimizar]");
        ejecutar(Files.createTempDirectory("consola interactiva ü-"),
                new ConstructorNativo.Herramientas(args[0],args[1],args[2],30),Arrays.asList(args).contains("--optimizar"));
    }
    static void ejecutar(Path dir,ConstructorNativo.Herramientas h,boolean optimizar) throws Exception {
        String fuente = "import runtime.Consola; { class Main { public main() { Consola c = new Consola(); "
                + "c.imprimir(\"Nombre: \" ); string nombre = c.leerLinea(); c.imprimir(\"Edad: \" ); "
                + "int edad = c.leerInt(); c.imprimirLinea(\"Hola, \" + nombre + \" (\" + edad + \")\"); } } }";
        Path archivo=dir.resolve("interactivo.txt"); Files.writeString(archivo,fuente);
        List<Main.ResultadoAnalisis> unidades=Main.analizarProyecto(archivo,fuente,false);
        VisorIr.Resultado ir=VisorIr.generar(unidades,archivo); exigir(ir.esExitoso(),ir.diagnosticos);
        ProyectoNativo proyecto=new ProyectoNativo(ir,unidades,archivo);
        ConstructorNativo.Artefacto a=ConstructorNativo.construir(proyecto.generar(DestinoNativoIr.LINUX_X64,"Main",optimizar),
                dir.resolve("interactivo nativo"),ConstructorNativo.Etapa.EJECUTABLE,h,new ProcesoNativo.Control(),s -> {});
        Sesion s=new Sesion(a,10);
        s.iniciar(); esperar(() -> s.out.toString().equals("Nombre: "),"No se vio la primera pregunta en vivo");
        Thread.sleep(150); exigir(s.hilo.isAlive() && s.error.get()==null,"Se cerró la entrada sin que el usuario enviara datos");
        s.entrada.enviar(NOMBRE+"\n"); esperar(() -> s.out.toString().equals("Nombre: Edad: "),"No se vio la segunda pregunta antes de enviar la edad");
        exigir(s.hilo.isAlive(),"No esperó la segunda respuesta");
        s.entrada.enviar("27\n"); s.terminar();
        exigir(s.resultado.get().codigo==0 && s.out.toString().equals(ESPERADO) && s.err.length()==0,"Diálogo nativo incorrecto");
        exigir(s.resultado.get().stdout().equals(s.out.toString()) && s.resultado.get().stderr().equals(s.err.toString()),"Captura final difiere de la salida en vivo");
        exigir(!s.entrada.estaAbierta(),"Retuvo el canal de entrada después de salir");
        rechaza(() -> s.entrada.enviar("otro\n"),"cerrada");
        Sesion eof=new Sesion(a,10); eof.iniciar(); esperar(() -> eof.out.toString().equals("Nombre: "),"EOF sin pregunta");
        eof.entrada.cerrar(); eof.terminar();
        exigir(eof.resultado.get().codigo==1 && eof.err.toString().contains("FIN_DE_ENTRADA"),"Cerrar entrada no produjo el EOF explícito");
        Sesion preparada=new Sesion(a,10); preparada.entrada.enviar(NOMBRE+"\n27\n"); preparada.entrada.cerrar(); preparada.iniciar(); preparada.terminar();
        exigir(preparada.resultado.get().codigo==0 && preparada.out.toString().equals(ESPERADO),"Cerrar entrada descartó datos pendientes");
        ProcesoNativo.EntradaInteractiva cola=new ProcesoNativo.EntradaInteractiva(); cola.enviar("x".repeat(ProcesoNativo.LIMITE_SALIDA));
        rechaza(() -> cola.enviar("x"),"límite"); cola.cerrar(); rechaza(() -> cola.enviar("x"),"cerrada"); cola.terminar();
        ProcesoNativo.Control cancelado=new ProcesoNativo.Control(); cancelado.cancelar();
        ProcesoNativo.EntradaInteractiva sinIniciar=new ProcesoNativo.EntradaInteractiva();
        rechaza(() -> ProcesoNativo.ejecutarInteractivo(Collections.singletonList(a.ejecutable.toString()),dir,sinIniciar,10,cancelado,salida -> {},error -> {}),"cancelada");
        exigir(!sinIniciar.estaAbierta(),"Fallo previo al arranque no cerró la sesión");
        canales(dir,h);
        interfaz(dir,proyecto,h,optimizar);
        System.out.println("Consola interactiva: "+total+" comprobaciones OK; artefactos: "+dir);
    }
    private static final class Sesion {
        final ProcesoNativo.EntradaInteractiva entrada=new ProcesoNativo.EntradaInteractiva();
        final ProcesoNativo.Control control=new ProcesoNativo.Control();
        final StringBuffer out=new StringBuffer(),err=new StringBuffer();
        final AtomicReference<ProcesoNativo.Resultado> resultado=new AtomicReference<>();
        final AtomicReference<Throwable> error=new AtomicReference<>();
        final Thread hilo;
        Sesion(ConstructorNativo.Artefacto a,int plazo) {
            hilo=new Thread(() -> { try { resultado.set(ConstructorNativo.ejecutarProgramaInteractivo(a,entrada,plazo,control,out::append,err::append)); }
                catch(Throwable fallo){error.set(fallo);} },"prueba-sesion-interactiva");
        }
        void iniciar(){hilo.start();}
        void terminar() throws Exception {
            hilo.join(12000); if(hilo.isAlive()){control.cancelar();hilo.join(3000);throw new AssertionError("Sesión bloqueada");}
            exigir(error.get()==null,"Fallo en sesión: "+error.get());
        }
    }
    private static void canales(Path dir,ConstructorNativo.Herramientas h) throws Exception {
        Path c=dir.resolve("utf8-fragmentado.c"),exe=dir.resolve("utf8-fragmentado");
        Files.writeString(c,"#define _POSIX_C_SOURCE 200809L\n#include <stdio.h>\n#include <time.h>\n"
                +"static void emitir(FILE *f,const unsigned char *s){for(;*s;s++){fputc(*s,f);fflush(f);struct timespec t={0,2000000};nanosleep(&t,0);}}\n"
                +"int main(int n,char **a){(void)a;if(n>1){for(int i=0;i<2000000;i++)putchar('X');fflush(stdout);return 0;}emitir(stdout,(const unsigned char*)\"¡🙂?\");emitir(stderr,(const unsigned char*)\"é🌵\");return getchar()==EOF?3:0;}\n");
        ProcesoNativo.Resultado compilado=ProcesoNativo.ejecutar(Arrays.asList(h.cc,c.toString(),"-o",exe.toString()),dir,new byte[0],10,new ProcesoNativo.Control());
        exigir(compilado.codigo==0,compilado.stderr());
        ProcesoNativo.EntradaInteractiva entrada=new ProcesoNativo.EntradaInteractiva();
        StringBuffer out=new StringBuffer(),err=new StringBuffer(); AtomicReference<Throwable> error=new AtomicReference<>();
        AtomicReference<ProcesoNativo.Resultado> resultado=new AtomicReference<>();
        Thread t=new Thread(() -> {try{resultado.set(ProcesoNativo.ejecutarInteractivo(Collections.singletonList(exe.toString()),dir,entrada,10,new ProcesoNativo.Control(),out::append,err::append));}catch(Throwable e){error.set(e);}});
        t.start(); esperar(() -> out.toString().equals("¡🙂?") && err.toString().equals("é🌵"),"UTF-8 fragmentado no llegó íntegro a ambos canales");
        entrada.cerrar();t.join(5000);exigir(!t.isAlive() && error.get()==null && resultado.get().codigo==3,"No aplicó EOF al proceso UTF-8");
        exigir(resultado.get().stdout().equals(out.toString()) && resultado.get().stderr().equals(err.toString()),"UTF-8 en vivo/final inconsistente");
        ProcesoNativo.EntradaInteractiva plazo=new ProcesoNativo.EntradaInteractiva(); out.setLength(0);err.setLength(0);
        try {ProcesoNativo.ejecutarInteractivo(Collections.singletonList(exe.toString()),dir,plazo,1,new ProcesoNativo.Control(),out::append,err::append);throw new AssertionError("No aplicó plazo");}
        catch(ProcesoNativo.Fallo e){exigir(e.getMessage().contains("límite") && e.parcial.stdout().equals("¡🙂?") && e.parcial.stderr().equals("é🌵"),"Plazo perdió el diagnóstico/salida parcial");}
        exigir(!plazo.estaAbierta() && out.toString().equals("¡🙂?") && err.toString().equals("é🌵"),"Plazo dejó sesión abierta o perdió salida en vivo");
        ProcesoNativo.EntradaInteractiva cortar=new ProcesoNativo.EntradaInteractiva(); ProcesoNativo.Control control=new ProcesoNativo.Control();
        out.setLength(0);err.setLength(0);error.set(null);
        Thread cancelable=new Thread(() -> {try{ProcesoNativo.ejecutarInteractivo(Collections.singletonList(exe.toString()),dir,cortar,10,control,out::append,err::append);}catch(Throwable e){error.set(e);}});
        cancelable.start();esperar(() -> out.toString().equals("¡🙂?") && err.toString().equals("é🌵"),"Cancelación sin pregunta visible");
        control.cancelar();cancelable.join(5000);exigir(!cancelable.isAlive() && error.get()!=null && error.get().getMessage().contains("cancelada") && !cortar.estaAbierta(),"Cancelar dejó procesos o entrada abiertos");
        ProcesoNativo.EntradaInteractiva exceso=new ProcesoNativo.EntradaInteractiva(); exceso.enviar("x".repeat(ProcesoNativo.LIMITE_SALIDA));
        out.setLength(0);err.setLength(0);
        try {ProcesoNativo.ejecutarInteractivo(Arrays.asList(exe.toString(),"exceso"),dir,exceso,5,new ProcesoNativo.Control(),out::append,err::append);throw new AssertionError("No limitó la salida en vivo");}
        catch(ProcesoNativo.Fallo e){exigir(e.getMessage().contains("Salida excede") && e.parcial.salida.length<=ProcesoNativo.LIMITE_SALIDA,"Límite de salida perdió la captura acotada");}
        exigir(!exceso.estaAbierta() && out.length()<=ProcesoNativo.LIMITE_SALIDA,"Exceso dejó un escritor bloqueado o sobrepasó la captura");
    }
    private static void interfaz(Path dir,ProyectoNativo proyecto,ConstructorNativo.Herramientas h,boolean optimizar) throws Exception {
        AtomicReference<VisorNativo> vista=new AtomicReference<>(); SwingUtilities.invokeAndWait(() -> vista.set(new VisorNativo(proyecto)));PruebasH6.esperar(vista.get());
        if(optimizar){SwingUtilities.invokeAndWait(() -> vista.get().seleccionarOptimizacion(true));PruebasH6.esperar(vista.get());}
        SwingUtilities.invokeAndWait(() -> {vista.get().usarHerramientas(h);vista.get().construirEn(dir.resolve("gui interactiva"),ConstructorNativo.Etapa.EJECUTABLE);});PruebasH6.esperar(vista.get());
        VisorNativo v=vista.get();
        SwingUtilities.invokeAndWait(() -> pulsar(v,"Ejecutar")); esperarGui(v,() -> v.salidaConsola().equals("Nombre: "));
        SwingUtilities.invokeAndWait(() -> {
            exigir(v.estaOcupado() && boton(v,"Enviar").isEnabled(),"GUI terminó antes de recibir respuestas");
            exigir(pestanaVisible(v,"Consola"),"Ejecución no abrió la consola");
            v.entradaConsola(NOMBRE);pulsar(v,"Enviar");
        });
        esperarGui(v,() -> v.salidaConsola().equals("Nombre: Edad: "));
        SwingUtilities.invokeAndWait(() -> {
            v.entradaConsola("27"); JTextArea campo=entrada(v);
            campo.getActionMap().get("enviar").actionPerformed(new ActionEvent(campo,0,"enviar"));
        });
        PruebasH6.esperar(v);
        SwingUtilities.invokeAndWait(() -> exigir(v.salidaConsola().equals(ESPERADO) && v.erroresConsola().isEmpty()
                && v.estadoActual().contains("Ejecución: código 0") && !boton(v,"Enviar").isEnabled(),"GUI no completó el diálogo por turnos"));
        SwingUtilities.invokeAndWait(() -> pulsar(v,"Ejecutar"));esperarGui(v,() -> v.salidaConsola().equals("Nombre: "));
        SwingUtilities.invokeAndWait(() -> pulsar(v,"Cerrar entrada"));PruebasH6.esperar(v);
        SwingUtilities.invokeAndWait(() -> exigir(v.erroresConsola().contains("FIN_DE_ENTRADA") && v.estadoActual().contains("Ejecución: código 1")
                && pestanaVisible(v,"Errores"),"GUI no mostró EOF al cerrar explícitamente"));
        SwingUtilities.invokeAndWait(() -> pulsar(v,"Ejecutar"));esperarGui(v,() -> v.salidaConsola().equals("Nombre: "));
        SwingUtilities.invokeAndWait(() -> {v.entradaConsola("");pulsar(v,"Enviar");});esperarGui(v,() -> v.salidaConsola().equals("Nombre: Edad: "));
        SwingUtilities.invokeAndWait(() -> {v.entradaConsola("27");pulsar(v,"Enviar");});PruebasH6.esperar(v);
        SwingUtilities.invokeAndWait(() -> exigir(v.salidaConsola().equals("Nombre: Edad: Hola,  (27)\n"),"Enviar vacío no envió una línea vacía"));
        SwingUtilities.invokeAndWait(() -> pulsar(v,"Ejecutar"));esperarGui(v,() -> v.salidaConsola().equals("Nombre: "));
        SwingUtilities.invokeAndWait(() -> {pulsar(v,"Cancelar");exigir(!boton(v,"Enviar").isEnabled() && !v.estaOcupado(),"Cancelar dejó habilitada la entrada");});
        Thread.sleep(150);
        SwingUtilities.invokeAndWait(() -> {
            exigir(v.estadoActual().contains("cancelada") && v.salidaConsola().equals("Nombre: "),"Callbacks tardíos alteraron una sesión cancelada");
            v.usarEntradaInteractiva(false);v.entradaConsola(NOMBRE+"\n27\n");pulsar(v,"Ejecutar");
            exigir(!boton(v,"Enviar").isEnabled(),"Modo preparado habilitó Enviar");
        });PruebasH6.esperar(v);
        SwingUtilities.invokeAndWait(() -> {
            exigir(v.salidaConsola().equals(ESPERADO) && v.erroresConsola().isEmpty(),"Regresión del modo preparado");
            v.usarEntradaInteractiva(true);v.entradaConsola(NOMBRE+"\n27");pulsar(v,"Ejecutar");
        });PruebasH6.esperar(v);
        SwingUtilities.invokeAndWait(() -> {
            exigir(v.salidaConsola().equals(ESPERADO),"Entrada inicial interactiva sin LF final no llegó completa");
            pulsar(v,"Ejecutar");
        });esperarGui(v,() -> v.salidaConsola().equals("Nombre: "));
        SwingUtilities.invokeAndWait(() -> {v.marcarDesactualizado();exigir(!v.estaOcupado() && !boton(v,"Enviar").isEnabled(),"Invalidación dejó abierta la consola");});
    }
    private static void esperarGui(VisorNativo v,BooleanSupplier condicion) throws Exception {
        esperar(() -> {boolean[] ok={false};try{SwingUtilities.invokeAndWait(() -> ok[0]=condicion.getAsBoolean());}catch(Exception e){throw new AssertionError(e);}return ok[0];},"GUI no mostró salida mientras esperaba entrada: "+v.diagnosticos());
    }
    private static void esperar(BooleanSupplier condicion,String mensaje) throws Exception {
        long fin=System.nanoTime()+10_000_000_000L;
        while(!condicion.getAsBoolean()){if(System.nanoTime()>fin)throw new AssertionError(mensaje);Thread.sleep(10);}
        total++;
    }
    private static JButton boton(Container c,String texto){for(Component h:c.getComponents()){if(h instanceof JButton && ((JButton)h).getText().equals(texto))return (JButton)h;if(h instanceof Container){JButton b=boton((Container)h,texto);if(b!=null)return b;}}return null;}
    private static JTextArea entrada(Container c){for(Component h:c.getComponents()){if(h instanceof JTextArea && ((JTextArea)h).isEditable())return (JTextArea)h;if(h instanceof Container){JTextArea t=entrada((Container)h);if(t!=null)return t;}}return null;}
    private static boolean pestanaVisible(Container c,String texto){for(Component h:c.getComponents()){if(h instanceof JTabbedPane){JTabbedPane t=(JTabbedPane)h;int n=t.indexOfTab(texto);if(n>=0)return t.getSelectedIndex()==n;}if(h instanceof Container && pestanaVisible((Container)h,texto))return true;}return false;}
    private static void pulsar(Container c,String texto){JButton b=boton(c,texto);exigir(b!=null && b.isEnabled(),"Botón deshabilitado: "+texto);b.doClick();}
    private interface Accion {void ejecutar()throws Exception;}
    private static void rechaza(Accion a,String mensaje)throws Exception{try{a.ejecutar();throw new AssertionError("Debió rechazar "+mensaje);}catch(java.io.IOException e){exigir(e.getMessage().contains(mensaje),"Diagnóstico inesperado: "+e);}}
    private static void exigir(boolean ok,String mensaje){total++;if(!ok)throw new AssertionError(mensaje);}
}
