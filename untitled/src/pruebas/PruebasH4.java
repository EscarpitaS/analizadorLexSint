import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** H4: layout constante y puentes que enlazan con el runtime C real. */
public final class PruebasH4 {
    private static int casos;
    private static final String FUENTE = "import runtime.Consola; { public class Base { int n = 11; public int valor() { return n; } } "
            + "public class D extends Base { public int n = 7; public int valor() { return n; } } public class Main { public main() { "
            + "Consola c = new Consola(); Base d = new D(); list<int> xs = [1, 2]; xs[1] += 1; "
            + "list<float> fs = [1, 2.5]; list<list<int>> rows = [xs]; int q = 7 / 2; float f = 7.0 / 2.0; "
            + "string s = \"A\0ñ\"; string otro = s + null; bool eq = s == otro; "
            + "c.imprimir(s); c.imprimirLinea(d); c.imprimirLinea(fs); c.imprimirLinea(rows); c.imprimirLinea(xs); "
            + "c.imprimirLinea(q); c.imprimirLinea(f); c.imprimirLinea(true); c.imprimirLinea(null); "
            + "int ri = c.leerInt(); float rf = c.leerFloat(); bool rb = c.leerBool(); string rs = c.leerLinea(); } } }";
    public static void main(String[] args) throws Exception {
        ModuloAtomicoIr m = ejecutar();
        if (args.length == 1) nativo(m, Path.of(args[0]));
    }
    static ModuloAtomicoIr ejecutar() {
        casos = 0; Path fuente = null;
        try {
            fuente = Files.createTempFile("h4-", ".txt");
            ResultadoGeneracionIr r = new GeneradorIr().generar(Main.analizarProyecto(fuente, FUENTE, false), "Main");
            comprobar(r.esExitoso(), "Frontend H4: " + r.errores);
            ModuloAtomicoIr m = new LinealizadorIr().transformar(r.modulo); DatosNativosIr d = new DatosNativosIr(m);
            LayoutProgramaIr.Clase base = d.clases.get("Base"), derivada = d.clases.get("D");
            comprobar(base.tamano == 24 && derivada.tamano == 32, "Prefijo heredado y campos homónimos");
            comprobar(new ArrayList<>(derivada.campos.values()).equals(Arrays.asList(16, 24)), "Offsets antes de emitir");
            comprobar(derivada.virtuales.get(0).propietaria.equals("D"), "Override en slot heredado");
            comprobar(DatosNativosIr.utf8("A\0ñ").length == 4, "Longitud UTF-8 con NUL");
            comprobar(d.strings.containsKey("A\0ñ"), "Pool de constantes");
            comprobar(d.tipos.containsKey(TipoIr.lista(TipoIr.lista(TipoIr.INT))), "Descriptores anidados");
            comprobar(d.tipos.containsKey(TipoIr.NULO), "Descriptor para conversión null");
            comprobar(d.origen(OrigenIr.GENERADO).equals("rt_origen_0"), "Origen de envoltorio explícito");
            Set<HelperAtomicoIr> helpers = EnumSet.noneOf(HelperAtomicoIr.class); Set<RuntimeConsolaId> consola = EnumSet.noneOf(RuntimeConsolaId.class);
            for (LlamadaAtomica l : llamadas(m)) {
                if (l.helper != null) helpers.add(l.helper); if (l.consola != null) consola.add(l.consola);
                comprobar(SoporteNativoIr.destino(l).startsWith("rt_"), "Binding C cerrado");
            }
            comprobar(helpers.equals(EnumSet.allOf(HelperAtomicoIr.class)), "Todos los helpers tienen binding");
            comprobar(consola.equals(EnumSet.allOf(RuntimeConsolaId.class)), "Todos los intrínsecos tienen binding");
            String nasm = new SoporteNativoIr().nasm(m), gas = new SoporteNativoIr().gas(m);
            comprobar(nasm.equals(new SoporteNativoIr().nasm(m)), "Exportación determinista");
            comprobar(nasm.contains("db 65, 0, 195, 177") && nasm.contains("dq 4, 0"), "Constante por bytes, no escapes");
            comprobar(nasm.contains("movq rdi, xmm0") && nasm.contains("mov rdx, r10"), "Float por bits y origen antes de C");
            comprobar(nasm.contains("push rbp\n    mov rbp, rsp") && nasm.contains("pop rbp\n    ret"), "Prólogo alineado");
            comprobar(gas.contains(".section .note.GNU-stack"), "Pila no ejecutable");
            comprobar(nasm.contains("rt_entry_init:") && nasm.contains("fn_C4_Main_M7_init_IR_P0"), "Plan de entrada preservado");
            comprobar(!nasm.contains("global main"), "Soporte H4 sin inventar backend H5");
            System.out.println("OK: " + casos + " comprobaciones H4 de datos nativos y enlaces ABI."); return m;
        } catch (IOException e) { throw new AssertionError(e); }
        finally { if (fuente != null) try { Files.deleteIfExists(fuente); } catch (IOException e) { throw new AssertionError(e); } }
    }
    private static void comprobar(boolean ok, String mensaje) { casos++; if (!ok) throw new AssertionError(mensaje); }
    private static List<LlamadaAtomica> llamadas(ModuloAtomicoIr m) {
        Map<String, LlamadaAtomica> llamadas = new LinkedHashMap<>();
        for (FuncionAtomicaIr f : m.funciones) for (CuadruploIr q : f.codigo) if (q.operacion == CuadruploIr.Operacion.LLAMAR_RUNTIME)
            llamadas.putIfAbsent(q.fuente1.llamada.simbolo, q.fuente1.llamada);
        return new ArrayList<>(llamadas.values());
    }
    private static String helper(ModuloAtomicoIr m, HelperAtomicoIr op, TipoIr retorno, TipoIr... parametros) {
        for (LlamadaAtomica l : llamadas(m)) if (l.helper == op && l.retorno.equals(retorno) && l.parametros.equals(Arrays.asList(parametros))) return l.simbolo;
        throw new AssertionError("Helper no encontrado: " + op);
    }
    private static void nativo(ModuloAtomicoIr m, Path carpeta) throws Exception {
        Path soporte = carpeta.resolve("soporte.S"), puentes = carpeta.resolve("harness.S"), driver = carpeta.resolve("driver.c"), exe = carpeta.resolve("puentes_h4");
        Files.writeString(soporte, new SoporteNativoIr().gas(m)); StringBuilder asm = new StringBuilder(".intel_syntax noprefix\n.text\n");
        String div = helper(m, HelperAtomicoIr.DIVIDIR_INT, TipoIr.INT, TipoIr.INT, TipoIr.INT);
        puente(asm, "test_div", div, "rdx");
        puente(asm, "test_div_float", helper(m, HelperAtomicoIr.DIVIDIR_FLOAT, TipoIr.FLOAT, TipoIr.FLOAT, TipoIr.FLOAT), "rdi");
        puente(asm, "test_new", helper(m, HelperAtomicoIr.NUEVO_OBJETO, TipoIr.objeto("D")), "rdi");
        puente(asm, "test_list", helper(m, HelperAtomicoIr.NUEVA_LISTA, TipoIr.lista(TipoIr.INT), TipoIr.INT), "rsi");
        puente(asm, "test_as_float", helper(m, HelperAtomicoIr.A_STRING, TipoIr.STRING, TipoIr.FLOAT), "rdi");
        puente(asm, "test_as_list", helper(m, HelperAtomicoIr.A_STRING, TipoIr.STRING, TipoIr.lista(TipoIr.INT)), "rsi");
        puente(asm, "test_concat", helper(m, HelperAtomicoIr.CONCATENAR, TipoIr.STRING, TipoIr.STRING, TipoIr.STRING), "rdx");
        puente(asm, "test_eq", helper(m, HelperAtomicoIr.IGUAL_STRING, TipoIr.BOOL, TipoIr.STRING, TipoIr.STRING), "rdx");
        puente(asm, "test_index", helper(m, HelperAtomicoIr.COMPROBAR_INDICE, TipoIr.VOID, TipoIr.lista(TipoIr.INT), TipoIr.INT), "rdx");
        puente(asm, "test_print", "rt_lang_CONSOLA_IMPRIMIR_LINEA", "rdx");
        puente(asm, "test_read_float", "rt_lang_CONSOLA_LEER_FLOAT", "rsi");
        puente(asm, "test_read_bool", "rt_lang_CONSOLA_LEER_BOOL", "rsi");
        puente(asm, "test_read_int", "rt_lang_CONSOLA_LEER_INT", "rsi");
        puente(asm, "test_read_line", "rt_lang_CONSOLA_LEER_LINEA", "rsi");
        asm.append(".global test_preserve\ntest_preserve:\n push rbp\n mov rbp,rsp\n push rbx\n push r12\n push r13\n push r14\n push r15\n sub rsp,8\n")
                .append(" mov rbx,111\n mov r12,222\n mov r13,333\n mov r14,444\n mov r15,555\n mov r10,rdi\n mov rdi,7\n mov rsi,2\n call ").append(div).append("\n")
                .append(" cmp rax,3\n jne .bad\n cmp rbx,111\n jne .bad\n cmp r12,222\n jne .bad\n cmp r13,333\n jne .bad\n cmp r14,444\n jne .bad\n cmp r15,555\n jne .bad\n xor eax,eax\n jmp .done\n.bad:\n mov eax,1\n.done:\n add rsp,8\n pop r15\n pop r14\n pop r13\n pop r12\n pop rbx\n pop rbp\n ret\n.section .note.GNU-stack,\"\",@progbits\n");
        Files.writeString(puentes, asm.toString());
        StringBuilder c = new StringBuilder("#include \"rt_runtime.h\"\n#include <assert.h>\n#include <string.h>\n#include <stdlib.h>\n");
        for (FuncionAtomicaIr f : m.funciones) {
            String name = LayoutProgramaIr.nombreFuncion(f.firma);
            if (f.firma.retorno.equals(TipoIr.VOID)) c.append("void ").append(name).append("(RtObject *r) {(void)r;}\n");
            else c.append("int64_t ").append(name).append("(RtObject *r) {return (int64_t)r->fields[").append(f.firma.propietaria.equals("D") ? 1 : 0).append("];}\n");
        }
        c.append("extern const RtClass rt_clase_C1_D;\nextern const RtClass *rt_entry_class;\n")
                .append("int64_t test_div(int64_t,int64_t,const RtOrigin*); double test_div_float(double,double,const RtOrigin*);\n")
                .append("RtObject *test_new(const RtOrigin*); RtList *test_list(int64_t,const RtOrigin*);\n")
                .append("RtString *test_as_float(double,const RtOrigin*); RtString *test_as_list(RtList*,const RtOrigin*);\n")
                .append("RtString *test_concat(RtString*,RtString*,const RtOrigin*); uint64_t test_eq(RtString*,RtString*,const RtOrigin*);\n")
                .append("void test_index(RtList*,int64_t,const RtOrigin*); void test_print(RtObject*,RtString*,const RtOrigin*);\n")
                .append("double test_read_float(RtObject*,const RtOrigin*); uint64_t test_read_bool(RtObject*,const RtOrigin*);\n")
                .append("int64_t test_read_int(RtObject*,const RtOrigin*); RtString *test_read_line(RtObject*,const RtOrigin*); int test_preserve(const RtOrigin*);\n")
                .append("static void text(RtString*s,const char*t){assert(s->length==strlen(t)&&!memcmp(s->bytes,t,(size_t)s->length));}\n")
                .append("static int entry_calls; static void entry_init(RtObject*r){assert(entry_calls==0&&r->fields[0]==0&&r->fields[1]==0);r->fields[1]=7;entry_calls=1;}\n")
                .append("static void entry_main(RtObject*r){assert(entry_calls==1&&r->fields[1]==7);entry_calls=2;}\n")
                .append("int main(int argc,char **argv){(void)argv; rt_program_init(); RtOrigin o={\"puente\",42,9}; if(argc>1){test_div(7,0,&o);return 99;}\n")
                .append("assert(rt_entry_class->size==16); assert(rt_clase_C1_D.size==32&&rt_clase_C1_D.base->size==24);\n")
                .append("RtObject *d=test_new(&o); assert(d->klass==&rt_clase_C1_D&&d->fields[0]==0&&d->fields[1]==0); d->fields[0]=11;d->fields[1]=7;\n")
                .append("int64_t(*virtual_method)(RtObject*);memcpy(&virtual_method,&d->vtable[0],sizeof(virtual_method));assert(virtual_method(d)==7);\n")
                .append("assert(test_div(INT64_MIN,-1,&o)==INT64_MIN&&test_div(-7,2,&o)==-3); assert(test_div_float(7,2,&o)==3.5);assert(test_preserve(&o)==0);\n")
                .append("text(test_as_float(1e23,&o),\"9.999999999999999E22\"); RtList *xs=test_list(2,&o);xs->items[0]=1;xs->items[1]=9;test_index(xs,1,&o);text(test_as_list(xs,&o),\"[1, 9]\");\n")
                .append("RtString *s=rt_string_new((const unsigned char*)\"A\\0\\xc3\\xb1\",4,&o); assert(test_eq(s,s,&o));text(test_concat(NULL,NULL,&o),\"nullnull\");test_print(d,s,&o);\n")
                .append("assert(test_read_float(d,&o)==1.25&&test_read_bool(d,&o)==1&&test_read_int(d,&o)==7);text(test_read_line(d,&o),\"texto\");assert(test_read_line(d,&o)==NULL);rt_program_shutdown();assert(rt_run_program(&rt_clase_C1_D,entry_init,entry_main)==0&&entry_calls==2);return 0;}\n");
        Files.writeString(driver, c.toString());
        String cc = System.getenv().getOrDefault("CC", "gcc");
        Process build = new ProcessBuilder(cc, "-std=c11", "-Wall", "-Wextra", "-Werror", "-pedantic", "-no-pie", "-I", "runtime/nativo/include",
                driver.toString(), soporte.toString(), puentes.toString(), carpeta.resolve("libanalizador_runtime.a").toString(), "-lm", "-o", exe.toString()).inheritIO().start();
        if (build.waitFor() != 0) throw new AssertionError("No compila el soporte ABI nativo");
        Process run = new ProcessBuilder(exe.toString()).start(); run.getOutputStream().write("1.25\ntrue\n7\ntexto\n".getBytes(StandardCharsets.UTF_8)); run.getOutputStream().close();
        byte[] salida = run.getInputStream().readAllBytes(), error = run.getErrorStream().readAllBytes();
        comprobar(run.waitFor() == 0 && Arrays.equals(salida, new byte[]{65,0,(byte)195,(byte)177,10}) && error.length == 0, "Puentes reales, descriptores y retornos");
        Process fallo = new ProcessBuilder(exe.toString(), "fallo").start(); fallo.getOutputStream().close();
        byte[] partial = fallo.getInputStream().readAllBytes(); String diagnostic = new String(fallo.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        comprobar(fallo.waitFor() == 1 && partial.length == 0 && diagnostic.equals("Error de ejecución [DIVISION_POR_CERO] \"puente\":42:9: DIVISION_POR_CERO\n"), "R10 conserva el origen real");
        System.out.println("OK: puentes H4 ensamblados con GNU as y enlazados con runtime C, sin JVM en el ejecutable.");
        String nasm = System.getenv("NASM");
        if (nasm != null && !nasm.isEmpty()) {
            Path fuenteNasm = carpeta.resolve("soporte.asm"), objetoNasm = carpeta.resolve("soporte_nasm.o"), ejecutableNasm = carpeta.resolve("puentes_nasm");
            Files.writeString(fuenteNasm, new SoporteNativoIr().nasm(m));
            Process assembler = new ProcessBuilder(nasm, "-Werror", "-f", "elf64", fuenteNasm.toString(), "-o", objetoNasm.toString()).inheritIO().start();
            comprobar(assembler.waitFor() == 0, "NASM acepta datos y puentes");
            Process link = new ProcessBuilder(cc, "-std=c11", "-Wall", "-Wextra", "-Werror", "-pedantic", "-no-pie", "-I", "runtime/nativo/include",
                    driver.toString(), objetoNasm.toString(), puentes.toString(), carpeta.resolve("libanalizador_runtime.a").toString(), "-lm", "-o", ejecutableNasm.toString()).inheritIO().start();
            comprobar(link.waitFor() == 0, "Enlace de soporte NASM");
            Process prueba = new ProcessBuilder(ejecutableNasm.toString()).start(); prueba.getOutputStream().write("1.25\ntrue\n7\ntexto\n".getBytes(StandardCharsets.UTF_8)); prueba.getOutputStream().close();
            byte[] bytes = prueba.getInputStream().readAllBytes(), stderr = prueba.getErrorStream().readAllBytes();
            comprobar(prueba.waitFor() == 0 && Arrays.equals(bytes, salida) && stderr.length == 0, "NASM y GNU as preservan ABI y bytes");
            Process errorNasm = new ProcessBuilder(ejecutableNasm.toString(), "fallo").start(); errorNasm.getOutputStream().close();
            comprobar(errorNasm.getInputStream().readAllBytes().length == 0
                    && new String(errorNasm.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).equals(diagnostic)
                    && errorNasm.waitFor() == 1, "Diagnóstico con origen también en NASM");
            System.out.println("OK: mismos puentes ensamblados con NASM/ELF64 y ejecutados sin JVM.");
        }

    }
    private static void puente(StringBuilder s, String nombre, String destino, String origen) {
        s.append(".global ").append(nombre).append('\n').append(nombre).append(":\n mov r10,").append(origen).append("\n jmp ").append(destino).append('\n');
    }
}
