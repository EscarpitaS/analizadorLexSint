import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Diferencial H7 con expectativas independientes, memoria acotada y origen de errores. */
public final class PruebasH7 {
    private static int total;
    private static final class Caso {
        final String nombre,cuerpo,entrada,salida;
        Caso(String n,String c,String in,String out){nombre=n;cuerpo=c;entrada=in;salida=out;}
    }
    private static final List<Caso> CASOS=Arrays.asList(
        new Caso("h7_constantes","int n=2+3*4; if(10>5){ c.imprimirLinea(n); }else{c.imprimirLinea(999);} while(false){c.imprimirLinea(888);}","","14\n"),
        new Caso("h7_longs","c.imprimirLinea(9223372036854775806<9223372036854775807); c.imprimirLinea(-9223372036854775808/-1);c.imprimirLinea(9223372036854775807*3);", "","true\n-9223372036854775808\n9223372036854775805\n"),
        new Caso("h7_registros","int x=c.leerInt();c.imprimirLinea(x*x+x*4-x);float a=c.leerFloat();c.imprimirLinea(a*a+a*4.0-a);","7\n1.25\n","70\n5.3125\n"),
        new Caso("h7_barreras","int x=1;c.imprimirLinea(x);x=3;c.imprimirLinea(x);list<int> a=[1];list<int> b=a;b[0]=7;c.imprimirLinea(a[0]);","","1\n3\n7\n"),
        new Caso("h7_presion","int a=c.leerInt();int b=c.leerInt();int d=c.leerInt();int e=c.leerInt();c.imprimirLinea((a+b)*(d+e)-(a*d+b*e)+(a*e+b*d));","2\n3\n5\n7\n","58\n"),
        new Caso("h7_ramas","int n=c.leerInt();if(n>0){n=5;}else{n=8;}c.imprimirLinea(n);while(n<10){n+=1;}c.imprimirLinea(n);","-1\n","8\n10\n"),
        new Caso("h7_signedzero","c.imprimirLinea(-0.0+0.0);c.imprimirLinea(-0.0*3.0);c.imprimirLinea(-(-0.0));c.imprimirLinea(0.0==-0.0);","","0.0\n-0.0\n0.0\ntrue\n"),
        new Caso("h7_saltos","if(true){if(false){c.imprimirLinea(9);}else{c.imprimirLinea(4);}}else{c.imprimirLinea(8);}repeat(false){c.imprimirLinea(7);}","","4\n"),
        new Caso("h7_error_no_usado","int ignorado=1/0;","",""),
        new Caso("h7_error_float_no_usado","float ignorado=3.0/-0.0;","",""),
        new Caso("h7_lectura_no_usada","int ignorado=c.leerInt();c.imprimirLinea(42);","abc\n","")
    );
    private static String fuente(String cuerpo){return "import runtime.Consola; { class Main { public main() { Consola c=new Consola(); "+cuerpo+" } } }";}
    public static void main(String[] args)throws Exception {
        if(args.length==2) { Path dir=Path.of(args[0]);DestinoNativoIr d=DestinoNativoIr.seleccionar(args[1]);
            PruebasH5.main(args);ejecutar(dir,d,true);
        }else ejecutar();
    }
    static void ejecutar(){try{ejecutar(Files.createTempDirectory("h7-ir-"),DestinoNativoIr.LINUX_X64,false);}catch(Exception e){throw new AssertionError("H7: "+e,e);}}
    private static void ejecutar(Path dir,DestinoNativoIr exportar,boolean corpus)throws Exception {
        total=0;Files.createDirectories(dir);
        for(Caso c:CASOS) {
            Path archivo=dir.resolve(c.nombre+".txt");Files.writeString(archivo,fuente(c.cuerpo));
            ModuloIr original=generar(archivo);Ejecucion esperado=correr(original,null,c.entrada.getBytes(StandardCharsets.UTF_8),Long.MAX_VALUE);
            String error=c.nombre.contains("error")?"DIVISION_POR_CERO":c.nombre.contains("lectura_no_usada")?"ENTRADA_INVALIDA":null;
            exigir(esperado.salida.equals(c.salida) && Objects.equals(esperado.codigo,error),"Oráculo H7: "+c.nombre+" "+esperado.salida+" "+esperado.codigo);
            Files.writeString(archivo.resolveSibling(c.nombre+".stdin"),c.entrada);
            Files.writeString(archivo.resolveSibling(c.nombre+".stdout"),esperado.salida);
            Files.writeString(archivo.resolveSibling(c.nombre+".stderr"),esperado.error);
            Files.writeString(archivo.resolveSibling(c.nombre+".estado"),error==null?"0":"1");
            probar(original,c.entrada.getBytes(StandardCharsets.UTF_8),esperado,dir,c.nombre,exportar);
        }
        if(corpus) {
            List<Path> fuentes=new ArrayList<>();try(var ps=Files.list(dir)){ps.filter(p->p.toString().endsWith(".txt") && !p.getFileName().toString().startsWith("h7_") && Files.exists(p.resolveSibling(p.getFileName().toString().replace(".txt",".asm")))).forEach(fuentes::add);}
            fuentes.sort(Comparator.comparing(Path::toString));
            for(Path archivo:fuentes) {String n=archivo.getFileName().toString().replace(".txt","");byte[] in=Files.readAllBytes(dir.resolve(n+".stdin"));ModuloIr m=generar(archivo);
                Ejecucion ref=correr(m,null,in,Long.MAX_VALUE);exigir(ref.salida.equals(Files.readString(dir.resolve(n+".stdout")))&&ref.error.equals(Files.readString(dir.resolve(n+".stderr"))),"Oráculo H5 alterado");probar(m,in,ref,dir,n,exportar);
            }
        }
        memoria(dir);aleatorias();gui(dir);
        System.out.println("OK: "+total+" comprobaciones H7; constantes, flujo, efectos, registros e IR original conservada.");
    }
    private static ModuloIr generar(Path archivo)throws Exception{ResultadoGeneracionIr r=new GeneradorIr().generar(Main.analizarProyecto(archivo),"Main");exigir(r.esExitoso(),"Frontend H7: "+r.errores);return r.modulo;}
    private static void probar(ModuloIr original,byte[] in,Ejecucion ref,Path dir,String nombre,DestinoNativoIr exportar)throws Exception {
        String antes=new ImpresorIr().imprimir(original);OptimizadorIr.Resultado opt=new OptimizadorIr().optimizar(original);
        exigir(antes.equals(new ImpresorIr().imprimir(original))&&opt.original==original,"Se mutó la IR original");
        exigir(opt.instruccionesDespues<=opt.instruccionesAntes && opt.bloquesDespues<=opt.bloquesAntes,"Transformación no reduce el programa");
        exigir(new ImpresorIr().imprimir(opt.optimizado).equals(new ImpresorIr().imprimir(new OptimizadorIr().optimizar(opt.optimizado).optimizado)),"H7 no es idempotente");
        igual(ref,correr(opt.optimizado,null,in,Long.MAX_VALUE),nombre+" tipada");
        for(DestinoNativoIr d:DestinoNativoIr.values()) {
            ModuloAtomicoIr atomica=new LinealizadorIr().transformar(opt.optimizado,d);
            igual(ref,correr(opt.optimizado,atomica,in,Long.MAX_VALUE),nombre+" "+d.id);
            String asm=new GeneradorEnsamblador(true).generar(atomica);exigir(asm.equals(new GeneradorEnsamblador(true).generar(atomica)),"ASM no determinista");
            if(nombre.equals("h7_constantes"))exigir(opt.instruccionesDespues<opt.instruccionesAntes&&opt.bloquesDespues<opt.bloquesAntes,"No plegó ni podó ramas");
            if(nombre.equals("h7_registros")) {
                int asignados=0;for(FuncionAtomicaIr f:atomica.funciones)asignados+=new AsignadorRegistrosNasm().asignar(f).size();
                exigir(asignados>0,"No asignó registros");
                String plano=new GeneradorEnsamblador().generar(atomica);
                exigir(asm.lines().filter(s->s.contains("[rbp")).count()<plano.lines().filter(s->s.contains("[rbp")).count(),"No redujo accesos a pila");
            }
            if(d==exportar) {Files.writeString(dir.resolve(nombre+".asm"),asm);Files.writeString(dir.resolve(nombre+".ir"),new ImpresorAtomicoIr().imprimir(atomica));Files.writeString(dir.resolve(nombre+".original.tipada.ir"),antes);Files.writeString(dir.resolve(nombre+".tipada.ir"),new ImpresorIr().imprimir(opt.optimizado));}
        }
    }
    private static final class Ejecucion {final String salida,error,codigo;Ejecucion(String out,ErrorEjecucionIr e){salida=out;error=e==null?"":e.diagnostico()+"\n";codigo=e==null?null:e.codigo;}}
    private static Ejecucion correr(ModuloIr m,ModuloAtomicoIr a,byte[] in,long memoria){ByteArrayOutputStream out=new ByteArrayOutputStream();ErrorEjecucionIr error=null;try{ConsolaReferencia c=new ConsolaReferencia(new ByteArrayInputStream(in),out);if(a==null)new InterpreteIr(memoria).ejecutar(m,c,1000000);else new InterpreteAtomicoIr(memoria).ejecutar(a,c,2000000);}catch(ErrorEjecucionIr e){error=e;}return new Ejecucion(new String(out.toByteArray(),StandardCharsets.UTF_8),error);}
    private static void igual(Ejecucion a,Ejecucion b,String caso){exigir(a.salida.equals(b.salida)&&a.error.equals(b.error),"Diferencia H7 "+caso+": "+a.salida+a.error+" vs "+b.salida+b.error);}
    private static void memoria(Path dir)throws Exception {
        for(String cuerpo:Arrays.asList("list<int> ignorada=[1];","Main ignorado=new Main();")) {
            Path archivo=dir.resolve("memoria.txt");Files.writeString(archivo,fuente(cuerpo));ModuloIr original=generar(archivo),opt=new OptimizadorIr().optimizar(original).optimizado;
            Ejecucion ref=correr(original,null,new byte[0],32);exigir(ref.codigo.equals("MEMORIA_AGOTADA"),"Fixture OOM no agotó memoria");
            igual(ref,correr(opt,null,new byte[0],32),"asignación no usada");
            for(DestinoNativoIr d:DestinoNativoIr.values())igual(ref,correr(opt,new LinealizadorIr().transformar(opt,d),new byte[0],32),"OOM "+d.id);
        }
    }
    private static void aleatorias() {
        Random random=new Random(7112026);
        for(int n=0;n<200;n++) {
            long x=random.nextLong(),y=random.nextLong();ValorIr a=ValorIr.temporal("a",TipoIr.INT),b=ValorIr.temporal("b",TipoIr.INT);
            OrigenIr o=new OrigenIr("prueba.txt",5,7);
            List<InstruccionIr> is=Arrays.asList(InstruccionIr.operacion(InstruccionIr.Operacion.MULTIPLICAR,a,o,ValorIr.entero(x),ValorIr.entero(y)),InstruccionIr.operacion(InstruccionIr.Operacion.SUMAR,b,o,a,ValorIr.entero(x)));
            FirmaIr firma=new FirmaIr("C","f",TipoIr.INT,FirmaIr.Visibilidad.PUBLICA,Collections.emptyList());
            FuncionIr f=new FuncionIr(firma,ValorIr.parametro("this",TipoIr.objeto("C")),Collections.emptyList(),Collections.emptyList(),Arrays.asList(new BloqueIr("inicio",is,TerminadorIr.retorno(b,o))));
            ModuloIr m=new ModuloIr("Random",Arrays.asList(new ClaseIr("C",null,Collections.emptyList())),Arrays.asList(f),null),opt=new OptimizadorIr().optimizar(m).optimizado;
            Object resultado=new InterpreteIr().invocar(opt,firma,new InterpreteIr.Referencia("C"),Collections.emptyList(),new ConsolaReferencia(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()),1000);
            exigir(resultado.equals(x*y+x),"Overflow/aritmética aleatoria");
        }
        // Un salto cíclico no puede convertirse en retorno ni desaparecer.
        FirmaIr main=new FirmaIr("C","main",TipoIr.VOID,FirmaIr.Visibilidad.PUBLICA,Collections.emptyList());
        FuncionIr f=new FuncionIr(main,ValorIr.parametro("this",TipoIr.objeto("C")),Collections.emptyList(),Collections.emptyList(),Arrays.asList(new BloqueIr("inicio",Collections.emptyList(),TerminadorIr.salto("b",OrigenIr.GENERADO)),new BloqueIr("b",Collections.emptyList(),TerminadorIr.salto("b",OrigenIr.GENERADO))));
        ModuloIr m=new ModuloIr("Ciclo",Arrays.asList(new ClaseIr("C",null,Collections.emptyList())),Arrays.asList(f),main),opt=new OptimizadorIr().optimizar(m).optimizado;
        try{new InterpreteIr().ejecutar(opt,new ConsolaReferencia(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()),20);throw new AssertionError("Ciclo eliminado");}catch(InterpreteIr.LimitePruebaIr esperado){total++;}
        // Rechazar primero una IR inválida, sin usar la optimización para disimular el defecto.
        ModuloIr malo=new ModuloIr("Malo",m.clases,Arrays.asList(new FuncionIr(main,f.receptor,Collections.emptyList(),Collections.emptyList(),Arrays.asList(new BloqueIr("mal",Collections.emptyList(),null)))),main);
        try{new OptimizadorIr().optimizar(malo);throw new AssertionError("Aceptó IR inválida");}catch(IllegalArgumentException esperado){total++;}
    }
    private static void gui(Path dir)throws Exception {
        Path p=dir.resolve("h7_constantes.txt");List<Main.ResultadoAnalisis> unidades=Main.analizarProyecto(p);ProyectoNativo proyecto=new ProyectoNativo(VisorIr.generar(unidades,p),unidades,p);
        ProyectoNativo.Preparado antes=proyecto.generar(DestinoNativoIr.LINUX_X64,"Main"),opt=proyecto.generar(DestinoNativoIr.LINUX_X64,"Main",true);
        exigir(opt.optimizado&&opt.atomicaOriginal.equals(antes.atomica)&&!opt.atomica.equals(antes.atomica),"Comparación gráfica no conserva original");
        exigir(opt.didactica.filas.size()<antes.didactica.filas.size(),"La comparación didáctica no usa la IR optimizada");
        exigir(opt.identidad().contains("optimizacion=H7")&&!antes.optimizado,"Identidad confunde perfiles de optimización");
        VisorNativo[] panel={null};SwingUtilities.invokeAndWait(()->panel[0]=new VisorNativo(proyecto));PruebasH6.esperar(panel[0]);
        SwingUtilities.invokeAndWait(()->panel[0].seleccionarOptimizacion(true));PruebasH6.esperar(panel[0]);
        SwingUtilities.invokeAndWait(()->{exigir(panel[0].ensambladorVisible().contains("; H7"),"Selector no activa backend optimizado");panel[0].seleccionarOptimizacion(false);});PruebasH6.esperar(panel[0]);
        SwingUtilities.invokeAndWait(()->{exigir(!panel[0].ensambladorVisible().contains("; H7"),"Selector no vuelve al backend original");panel[0].cancelar();});
    }
    private static void exigir(boolean b,String m){total++;if(!b)throw new AssertionError(m);}
}
