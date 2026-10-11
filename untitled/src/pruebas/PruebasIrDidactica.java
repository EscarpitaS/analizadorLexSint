import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Presentación portable: snapshots, identidad, referencias de tercias y conservación de la IR. */
public final class PruebasIrDidactica {
    public static void main(String[] args) { ejecutar(); }
    static void ejecutar() {
        exigir(PresentacionDidacticaIr.NOMBRES.size() == CuadruploIr.Operacion.values().length, "Faltan nombres de operaciones");
        String[] casos = {
            "public main() { int a=1; int b=2; int c=3; int d=4; a=b+c*d; float f=a; bool p=a>b; p=not p; a-=2; a/=2; }",
            "int a=1; public int cambiar() { a=9; return 2; } public main() { int r=a+cambiar(); a+=cambiar(); }",
            "public main() { if(true) { int x=2; } if(true) { int x=3; } int t0=1; int d0=2; int este=3; }",
            "public main() { list<int> xs=[1,2,3]; list<int> alias=xs; alias[1]+=4; repeat each(float x in xs) { float y=x; } Exist(xs) { int n=xs[0]; } }",
            "public float f(int a,float b,int c,float d,int e,float f,int g,int h) { return a+b+c+d+e+f+g+h; } public main() { float r=f(1,2.0,3,4.0,5,6.0,7,8); }",
            "public bool f() { return true; } public main() { bool x=false && f(); bool y=true || f(); while(x) { x=false; } }",
            "public main() { string s=\"t0\"; string otro=\"a,b\"; Consola c=new Consola(); c.imprimirLinea(s); float x=100000000000000000000000.0; c.imprimirLinea(x); }"
        };
        int cantidad = 0;
        Path temp = null;
        try {
            temp = Files.createTempDirectory("ir-didactica-");
            for (String cuerpo : casos) {
                Path fuente = temp.resolve("Main.txt");
                Files.writeString(fuente, "import runtime.Consola; { class Main { " + cuerpo + " } }");
                ResultadoGeneracionIr g = new GeneradorIr().generar(Main.analizarProyecto(fuente, false), "Main");
                exigir(g.esExitoso(), "Caso didáctico rechazado: " + g.errores + "\n" + cuerpo);
                cantidad += verificar(g.modulo);
                PresentacionDidacticaIr.Resultado p = PresentacionDidacticaIr.desdeTipada(g.modulo);
                if (cuerpo.contains("int t0=")) exigir(p.cuadruplosTsv.contains("t0#") && p.cuadruplosTsv.contains("x#"),
                        "Nombres locales/temporales u homónimos se confundieron");
                if (cuerpo.contains("\"t0\"")) exigir(p.triplosTsv.contains("\"t0\"")
                        && p.codigo.contains("9.999999999999999E22"), "Literal renombrado o float formateado con otro contrato");
                if (cuerpo.contains("a+cambiar()")) {
                    List<PresentacionDidacticaIr.Fila> main = new ArrayList<>();
                    for (PresentacionDidacticaIr.Fila fila : p.filas) if (fila.funcion.equals("Main::main()")) main.add(fila);
                    int carga = buscar(main, "LEER_MEMORIA", 0), llamada = buscar(main, "LLAMAR_VIRTUAL", carga), suma = buscar(main, "SUMAR", llamada);
                    exigir(carga < llamada && llamada < suma && main.get(suma).argumento1.referencia.equals(main.get(carga).resultado.referencia),
                            "La vista sustituyó el snapshot previo a la llamada por una nueva lectura de a");
                }
            }
            for (String nombre : List.of("H2_control.txt", "H2_consola.txt", "H3_objetos_listas.txt")) {
                Path fuente = temp.resolve("Ejemplo.txt");
                try (InputStream recurso = PruebasIrDidactica.class.getResourceAsStream("/ejemplos/ir/" + nombre)) {
                    exigir(recurso != null, "Ejemplo no empaquetado"); Files.copy(recurso, fuente, StandardCopyOption.REPLACE_EXISTING);
                }
                ResultadoGeneracionIr g = new GeneradorIr().generar(Main.analizarProyecto(fuente, false), "Programa");
                // Los ejemplos de consola y control seleccionan sus propias clases de entrada.
                if (!g.esExitoso()) g = new GeneradorIr().generar(Main.analizarProyecto(fuente, false), null);
                exigir(g.esExitoso(), "Ejemplo rechazado: " + nombre + " " + g.errores);
                cantidad += verificar(g.modulo);
            }
            System.out.println("OK: " + cantidad + " filas didácticas; snapshots, identidad, tercias y módulos originales conservados.");
        } catch (Exception e) { throw new AssertionError(e); }
        finally {
            if (temp != null) try (java.util.stream.Stream<Path> archivos = Files.walk(temp)) {
                for (Path p : (Iterable<Path>) archivos.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
            } catch (Exception e) { throw new AssertionError(e); }
        }
    }
    private static int buscar(List<PresentacionDidacticaIr.Fila> filas, String op, int inicio) {
        for (int i=inicio; i<filas.size(); i++) if (filas.get(i).operacion.equals(op)) return i;
        throw new AssertionError("No se encontró " + op);
    }
    private static int verificar(ModuloIr m) {
        ModuloAtomicoIr a = new LinealizadorIr().transformar(m);
        String original = new ImpresorIr().imprimir(m), fisica = new ImpresorAtomicoIr().imprimir(a);
        PresentacionDidacticaIr.Resultado p = PresentacionDidacticaIr.desdeTipada(m);
        String simplificada = PresentacionDidacticaIr.atomicaSimplificada(m,a);
        exigir(original.equals(new ImpresorIr().imprimir(m)) && fisica.equals(new ImpresorAtomicoIr().imprimir(a)), "La presentación alteró la IR");
        exigir(p.codigo.equals(PresentacionDidacticaIr.desdeTipada(m).codigo), "Proyección no determinista");
        exigir(!p.codigo.contains("[rbp") && !p.codigo.contains("bits64_") && !p.codigo.contains("fn_C")
                && !simplificada.contains("[rbp") && !simplificada.contains("bits64_") && !simplificada.contains("fn_C"), "Se filtraron operandos físicos");
        long cuads = simplificada.lines().filter(l -> l.stripLeading().startsWith("(")).count();
        exigir(cuads == a.funciones.stream().mapToLong(f -> f.codigo.size()).sum(), "El diccionario cambió la cantidad de cuádruplos físicos");
        for (int i=0; i<p.filas.size(); i++) {
            PresentacionDidacticaIr.Fila fila = p.filas.get(i);
            exigir(fila.numero==i && p.triplos.get(i).size()==6, "Numeración o tercias incorrectas");
            for (int j=3; j<=4; j++) {
                String arg = p.triplos.get(i).get(j);
                if (arg.matches("\\(\\d+\\)")) {
                    int destino = Integer.parseInt(arg.substring(1,arg.length()-1));
                    exigir(destino<p.filas.size() && p.filas.get(destino).produceValor
                            && p.filas.get(destino).funcion.equals(fila.funcion), "Referencia de tercia inválida o de otra función");
                }
            }
            if (!fila.produceValor && !fila.resultado.texto.equals("—"))
                exigir(!p.triplos.get(i).get(4).equals("—"), "La tercia perdió el destino de una escritura/etiqueta");
        }
        exigir(p.filas.stream().anyMatch(f -> f.operacion.equals("RETORNAR")), "Se omitió el control de retorno");
        return p.filas.size();
    }
    private static void exigir(boolean condicion, String mensaje) { if (!condicion) throw new AssertionError(mensaje); }
}
