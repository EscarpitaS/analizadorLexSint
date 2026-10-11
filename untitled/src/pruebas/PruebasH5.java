import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Oráculos de H5 para ambos perfiles y corpus nativo reutilizable. */
public final class PruebasH5 {
    private static int comprobaciones;
    private static final class Caso {
        final String nombre,fuente,salida,error; final byte[] entrada;
        Caso(String n,String f,String s,String e,byte[] in) { nombre=n;fuente=f;salida=s;error=e;entrada=in; }
    }
    private static final List<Caso> casos=new ArrayList<>();
    private static String programa(String miembros,String cuerpo) { return "import runtime.Consola; { public class Main { "+miembros+" public main() { Consola c=new Consola(); "+cuerpo.replace("1.0e308", "1"+"0".repeat(308)+".0").replace("1.0e23", "100000000000000000000000.0")+" } } }"; }
    private static void caso(String n,String miembros,String cuerpo,String salida) { casos.add(new Caso(n,programa(miembros,cuerpo),salida,null,new byte[0])); }
    private static void fallo(String n,String cuerpo,String codigo,byte[] entrada) { casos.add(new Caso(n,programa("",cuerpo),"",codigo,entrada)); }
    private static void corpus() {
        casos.clear();
        caso("escalares","","int a=7; int b=-2; c.imprimirLinea(a+b*3); c.imprimirLinea(a-b); c.imprimirLinea(a/b); c.imprimirLinea(not false); c.imprimirLinea(a>b); c.imprimirLinea(a>=7); c.imprimirLinea(b<a); c.imprimirLinea(b<=-2); c.imprimirLinea(a==7); c.imprimirLinea(a!=b);", "1\n9\n-3\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\n");
        caso("wrap","","int m=-9223372036854775808; c.imprimirLinea(9223372036854775807+1); c.imprimirLinea(-m); c.imprimirLinea(m/-1); c.imprimirLinea(9223372036854775807*2);", "-9223372036854775808\n-9223372036854775808\n-9223372036854775808\n-2\n");
        caso("float","","float z=-0.0; float inf=1.0e308*1.0e308; float nan=inf-inf; c.imprimirLinea(1+2.5); c.imprimirLinea(7.0/2); c.imprimirLinea(2.0-3.0); c.imprimirLinea(z); c.imprimirLinea(-z); c.imprimirLinea(1.0e23); c.imprimirLinea(inf); c.imprimirLinea(nan); c.imprimirLinea(nan==nan); c.imprimirLinea(nan!=nan); c.imprimirLinea(nan<1.0); c.imprimirLinea(nan<=1.0); c.imprimirLinea(nan>1.0); c.imprimirLinea(nan>=1.0); c.imprimirLinea(z==0.0); c.imprimirLinea(-3.0<2.0); c.imprimirLinea(2.0<=2.0); c.imprimirLinea(3.0>2.0); c.imprimirLinea(3.0>=3.0);", "3.5\n3.5\n-1.0\n-0.0\n0.0\n9.999999999999999E22\nInfinity\nNaN\nfalse\ntrue\nfalse\nfalse\nfalse\nfalse\ntrue\ntrue\ntrue\ntrue\ntrue\n");
        caso("control","","int n=0; while(n<3) { n+=1; c.imprimir(n); } repeat(n<5) { n+=1; c.imprimir(n); } if(n==5) { c.imprimir(\"Y\"); } else { c.imprimir(\"X\"); } bool b=false && c.leerBool(); bool d=true || c.leerBool(); c.imprimirLinea(b); c.imprimirLinea(d);", "12345Yfalse\ntrue\n");
        caso("recursion","int factorial(int n) { if(n<=1) { return 1; } return n*factorial(n-1); }", "c.imprimirLinea(factorial(10));", "3628800\n");
        caso("argumentos","public float mixto(float a,int b,float d,int e,float f,int g,float h,int i,float j,int k,float l,int m,float n,int o,float p,int q,float r,int s) { return a+b+d+e+f+g+h+i+j+k+l+m+n+o+p+q+r+s; }", "c.imprimirLinea(mixto(1.0,2,3.0,4,5.0,6,7.0,8,9.0,10,11.0,12,13.0,14,15.0,16,17.0,18));", "171.0\n");
        caso("strings","", "string s=\"A\0ñ😀\"; c.imprimir(s); c.imprimirLinea(s==\"A\0ñ😀\"); c.imprimirLinea(s!=\"A\"); string nil=null; c.imprimirLinea(nil==null); c.imprimirLinea(\"x\"+nil+4+true);", "A\0ñ😀true\ntrue\ntrue\nxnull4true\n");
        caso("listas","", "list<int> xs=[1,2,3]; list<int> alias=xs; alias[1]+=5; list<list<int>> rows=[xs,[]]; c.imprimirLinea(rows); c.imprimirLinea(xs==alias); c.imprimirLinea(xs==[1,7,3]); repeat each(float x in xs) { c.imprimirLinea(x+0.5); } Exist(xs) { c.imprimir(\"Y\"); } Exist([]) { c.imprimir(\"X\"); } list<string> ss=[null,\"x\"]; ss[0]+=4; c.imprimirLinea(ss);", "[[1, 7, 3], []]\ntrue\nfalse\n1.5\n7.5\n3.5\nY[null4, x]\n");
        casos.add(new Caso("herencia","import runtime.Consola; { public class Base { public int n=2; public int primero=valor(); public int valor() { return 1; } } public class D extends Base { public int n=5; public int valor() { return n; } } public class Main { public main() { Consola c=new Consola(); D d=new D(); Base b=d; b.n+=1; d.n+=2; c.imprimirLinea(d.primero); c.imprimirLinea(b.valor()); c.imprimirLinea(b.n); c.imprimirLinea(d.n); c.imprimirLinea(d); c.imprimirLinea(b==d); } } }", "0\n7\n3\n7\n<D>\ntrue\n",null,new byte[0]));
        caso("orden","int n=1; int cambiar() { n=9; return 2; }", "c.imprimirLinea(n+cambiar()); c.imprimirLinea(n);", "3\n9\n");
        caso("destino","list<int> xs=[1]; int cambiar() { xs=[9]; return 2; }", "list<int> anterior=xs; xs[0]+=cambiar(); c.imprimirLinea(anterior); c.imprimirLinea(xs);", "[3]\n[9]\n");
        caso("snapshots","list<int> xs=[1]; int cambiar() { xs[0]=9; return 2; }", "c.imprimirLinea(\"lista=\"+xs+cambiar()); c.imprimirLinea(xs);", "lista=[1]2\n[9]\n");
        casos.add(new Caso("lecturas",programa("","c.imprimirLinea(c.leerInt()); c.imprimirLinea(c.leerFloat()); c.imprimirLinea(c.leerBool()); c.imprimirLinea(c.leerLinea()); c.imprimirLinea(c.leerLinea());"),"-7\n1.25\ntrue\nñ\0😀\nnull\n",null," -7 \r\n1.25\ntrue\nñ\0😀\n".getBytes(StandardCharsets.UTF_8)));
        fallo("div_cero","int x=1/0; c.imprimir(x);","DIVISION_POR_CERO",new byte[0]);
        fallo("div_float_cero","float x=1.0/-0.0; c.imprimir(x);","DIVISION_POR_CERO",new byte[0]);
        fallo("nulo","list<int> xs=null; c.imprimir(xs[0]);","REFERENCIA_NULA",new byte[0]);
        fallo("indice","list<int> xs=[1]; c.imprimir(xs[-1]);","INDICE_FUERA_DE_RANGO",new byte[0]);
        fallo("limite","list<int> xs=[1]; c.imprimir(xs[1]);","INDICE_FUERA_DE_RANGO",new byte[0]);
        fallo("eof","c.imprimir(c.leerInt());","FIN_DE_ENTRADA",new byte[0]);
        fallo("input_prefijo","c.imprimir(c.leerFloat());","ENTRADA_INVALIDA","12abc\n".getBytes(StandardCharsets.UTF_8));
        fallo("input_hex","c.imprimir(c.leerFloat());","ENTRADA_INVALIDA","0x1.fp3\n".getBytes(StandardCharsets.UTF_8));
        fallo("input_nan","c.imprimir(c.leerFloat());","ENTRADA_INVALIDA","NaN\n".getBytes(StandardCharsets.UTF_8));
        fallo("rango","c.imprimir(c.leerInt());","ENTRADA_FUERA_DE_RANGO","9223372036854775808\n".getBytes(StandardCharsets.UTF_8));
        fallo("utf8","c.imprimir(c.leerLinea());","UTF8_INVALIDO",new byte[]{(byte)0xc0,(byte)0x80,10});
        casos.add(new Caso("imports","import Aux; import runtime.Consola; { public class D extends Base { public int valor() { return 7; } } public class Main { public main() { Consola c=new Consola(); Base b=new D(); c.imprimirLinea(b.valor()); c.imprimirLinea(b.n); } } }", "7\n2\n",null,new byte[0]));
        StringBuilder locales=new StringBuilder(); for(int i=0;i<620;i++) locales.append("int v").append(i).append('=').append(i).append(';');
        caso("marco_grande","",locales+"c.imprimirLinea(v0+v619);", "619\n");
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2) ejecutar(Path.of(args[0]),DestinoNativoIr.seleccionar(args[1]));
        else ejecutar();
    }
    static void ejecutar() { try { ejecutar(null,null); } catch(IOException e) { throw new AssertionError(e); } }
    private static void comprobar(boolean b,String texto) { comprobaciones++; if(!b) throw new AssertionError(texto); }
    private static final class Resultado {
        final byte[] salida,diagnostico; final String error;
        Resultado(byte[] salida,ErrorEjecucionIr e) { this.salida=salida;error=e==null?null:e.codigo;diagnostico=e==null?new byte[0]:(e.diagnostico()+"\n").getBytes(StandardCharsets.UTF_8); }
    }
    private static Resultado correr(ModuloIr tipada,ModuloAtomicoIr atomica,byte[] entrada) {
        ByteArrayOutputStream salida=new ByteArrayOutputStream(); ErrorEjecucionIr fallo=null;
        try {
            ConsolaReferencia c=new ConsolaReferencia(new ByteArrayInputStream(entrada),salida);
            if(atomica==null) new InterpreteIr().ejecutar(tipada,c,2000000);
            else new InterpreteAtomicoIr().ejecutar(atomica,c,4000000);
        } catch(ErrorEjecucionIr e) { fallo=e; }
        return new Resultado(salida.toByteArray(),fallo);
    }
    private static void ejecutar(Path carpeta,DestinoNativoIr exportar) throws IOException {
        comprobaciones=0;corpus();Set<CuadruploIr.Operacion> operaciones=EnumSet.noneOf(CuadruploIr.Operacion.class);
        Path temporal=carpeta==null?Files.createTempDirectory("h5-ir-"):carpeta;Files.createDirectories(temporal);
        try {
            Files.writeString(temporal.resolve("Aux.txt"), "{ public class Base { public int n=2; public int valor() { return n; } } public class Aux { public main() { } } }");
            for(Caso c:casos) {
                Path fuente=temporal.resolve(c.nombre+".txt");Files.writeString(fuente,c.fuente);
                ResultadoGeneracionIr r=new GeneradorIr().generar(Main.analizarProyecto(fuente),"Main");
                comprobar(r.esExitoso(),"Frontend "+c.nombre+": "+r.errores);
                Resultado ref=correr(r.modulo,null,c.entrada);
                comprobar(Arrays.equals(ref.salida,c.salida.getBytes(StandardCharsets.UTF_8)) && Objects.equals(ref.error,c.error),"Oráculo independiente "+c.nombre+": "+new String(ref.salida,StandardCharsets.UTF_8)+" error="+ref.error);
                for(DestinoNativoIr destino:DestinoNativoIr.values()) {
                    ModuloAtomicoIr m=new LinealizadorIr().transformar(r.modulo,destino);Resultado actual=correr(r.modulo,m,c.entrada);
                    comprobar(Arrays.equals(ref.salida,actual.salida) && Arrays.equals(ref.diagnostico,actual.diagnostico),"Referencia "+destino+" "+c.nombre);
                    String antes=new ImpresorAtomicoIr().imprimir(m), tipadaAntes=new ImpresorIr().imprimir(r.modulo);
                    String asm=new GeneradorEnsamblador().generar(m);
                    comprobar(antes.equals(new ImpresorAtomicoIr().imprimir(m)) && tipadaAntes.equals(new ImpresorIr().imprimir(r.modulo)), "Emisión no muta IR "+c.nombre);
                    if(c.nombre.equals("escalares")) {
                        DestinoNativoIr otro=destino==DestinoNativoIr.LINUX_X64?DestinoNativoIr.WINDOWS_X64:DestinoNativoIr.LINUX_X64;
                        ModuloAtomicoIr mezclado=new ModuloAtomicoIr(m.nombre,m.entrada,new LayoutProgramaIr(otro,m.layout.clases,m.layout.marcos),m.funciones);
                        comprobar(!new VerificadorAtomicoIr().verificar(mezclado).isEmpty(), "Rechazo de ABI mezclada");
                        boolean rechazo=false;
                        try { new GeneradorEnsamblador().generar(mezclado); } catch(IllegalArgumentException e) { rechazo=true; }
                        comprobar(rechazo,"El backend no exporta ABI mezclada");
                        List<FuncionAtomicaIr> fs=new ArrayList<>();
                        for(FuncionAtomicaIr f:m.funciones) {
                            List<CuadruploIr> qs=new ArrayList<>();
                            String primera=null;for(CuadruploIr q:f.codigo) if(q.operacion==CuadruploIr.Operacion.LABEL) { primera=q.destino.etiqueta;break; }
                            for(CuadruploIr q:f.codigo) {
                                OperandoAtomico a=q.fuente1,b=q.fuente2,d=q.destino;
                                if(a!=null && a.clase==OperandoAtomico.Clase.ETIQUETA && a.etiqueta.equals(primera)) a=OperandoAtomico.etiqueta("main");
                                if(b!=null && b.clase==OperandoAtomico.Clase.ETIQUETA && b.etiqueta.equals(primera)) b=OperandoAtomico.etiqueta("main");
                                if(d!=null && d.clase==OperandoAtomico.Clase.ETIQUETA && d.etiqueta.equals(primera)) d=OperandoAtomico.etiqueta("main");
                                qs.add(new CuadruploIr(q.operacion,a,b,d,q.origen));
                            }
                            fs.add(new FuncionAtomicaIr(f.firma,f.receptor,f.parametros,qs));
                            if(primera!=null) { fs.addAll(m.funciones.subList(fs.size(),m.funciones.size()));break; }
                        }
                        rechazo=false;
                        try { new GeneradorEnsamblador().generar(new ModuloAtomicoIr(m.nombre,m.entrada,m.layout,fs)); } catch(IllegalArgumentException e) { rechazo=true; }
                        comprobar(rechazo,"Etiqueta reservada por la entrada CRT");
                    }
                    comprobar(asm.equals(new GeneradorEnsamblador().generar(m)),"NASM determinista "+c.nombre);
                    comprobar(asm.contains("global main") && !asm.contains("extern "+LayoutProgramaIr.nombreFuncion(m.entrada)+"\n"),"Cuerpos completos "+c.nombre);
                    for(FuncionAtomicaIr f:m.funciones) for(CuadruploIr q:f.codigo) operaciones.add(q.operacion);
                    if(destino==DestinoNativoIr.WINDOWS_X64) {
                        comprobar(asm.contains("section .pdata") && asm.contains("section .xdata") && !asm.contains("wrt ..plt") && !asm.contains(".note.GNU-stack"),"Formato Windows "+c.nombre);
                        for(LayoutProgramaIr.Marco marco:m.layout.marcos.values()) comprobar(marco.tamano%16==0 && marco.bytesSalientes>=32,"Frame Windows "+c.nombre);
                        if(c.nombre.equals("marco_grande")) comprobar(asm.contains("call ___chkstk_ms"),"Sondeo de pila Windows");
                    }
                    if(carpeta!=null && destino==exportar) {
                        Files.writeString(temporal.resolve(c.nombre+".asm"),asm);
                        Files.write(temporal.resolve(c.nombre+".stdin"),c.entrada);Files.write(temporal.resolve(c.nombre+".stdout"),ref.salida);
                        Files.write(temporal.resolve(c.nombre+".stderr"),ref.diagnostico);
                        Files.writeString(temporal.resolve(c.nombre+".estado"),c.error==null?"0":"1");
                    }
                }
            }
            comprobar(operaciones.equals(EnumSet.allOf(CuadruploIr.Operacion.class)),"Cobertura de operaciones: "+operaciones);
            comprobar(DestinoNativoIr.WINDOWS_X64.argumentos(Arrays.asList(TipoIr.objeto("Main"),TipoIr.FLOAT,TipoIr.INT,TipoIr.FLOAT,TipoIr.INT),false).toString().equals("[rcx, xmm1, r8, xmm3, [rsp+32]]"),"Posiciones Windows mixtas");
            System.out.println("OK: "+comprobaciones+" comprobaciones H5, "+casos.size()+" programas y todas las operaciones en ambos perfiles.");
        } finally {
            if(carpeta==null) { try(var paths=Files.walk(temporal)) { for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(p); } }
        }
    }
}
