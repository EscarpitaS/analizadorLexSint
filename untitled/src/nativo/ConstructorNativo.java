import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Construcción Java autónoma: runtime del JAR, herramientas externas y publicación por etapa. */
final class ConstructorNativo {
    enum Etapa { OBJETO, EJECUTABLE }
    static final class Herramientas {
        final String nasm, cc, ar;
        final int plazo;
        Herramientas(String nasm, String cc, String ar, int plazo) {
            this.nasm = requerido(nasm); this.cc = requerido(cc); this.ar = requerido(ar);
            if (plazo < 1 || plazo > 3600) throw new IllegalArgumentException("El plazo debe estar entre 1 y 3600 segundos");
            this.plazo = plazo;
        }
        private static String requerido(String s) {
            if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException("Indica el programa de cada herramienta");
            return s.trim();
        }
        static Herramientas predeterminadas(DestinoNativoIr d) {
            boolean win = d == DestinoNativoIr.WINDOWS_X64;
            String cc = entorno(win ? "CC_WINDOWS" : "CC", win && !hostWindows() ? "x86_64-w64-mingw32-gcc" : "gcc");
            String ar = entorno(win ? "AR_WINDOWS" : "AR", win && !hostWindows() ? "x86_64-w64-mingw32-ar" : "ar");
            String nasm = entorno("NASM", "nasm");
            Path raiz = raizDistribucion();
            String localNasm = hostWindows() ? "build/tools/nasm.exe" : "build/tools/nasm";
            if (nasm.equals("nasm") && raiz != null && Files.isExecutable(raiz.resolve(localNasm)))
                nasm = raiz.resolve(localNasm).toString();
            if (nasm.equals("nasm")) {
                try {
                    Path codigo = Paths.get(ConstructorNativo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    Path local = codigo.getParent().resolve("analizadorLexSint").resolve(localNasm);
                    if (Files.isExecutable(local)) nasm = local.toString();
                } catch (Exception ignored) { }
            }
            return new Herramientas(nasm, cc, ar, 120);
        }
        private static String entorno(String clave, String defecto) {
            String s = System.getenv(clave); return s == null || s.isBlank() ? defecto : s;
        }
    }
    static final class Artefacto {
        final Path carpeta, objeto, ejecutable;
        final ProyectoNativo.Preparado programa;
        Artefacto(Path carpeta, ProyectoNativo.Preparado programa, Etapa etapa) {
            this.carpeta = carpeta; this.programa = programa;
            objeto = carpeta.resolve("programa" + programa.destino.extensionObjeto);
            ejecutable = etapa == Etapa.EJECUTABLE ? carpeta.resolve(programa.destino == DestinoNativoIr.WINDOWS_X64 ? "programa.exe" : "programa") : null;
        }
    }
    static Artefacto construir(ProyectoNativo.Preparado p, Path salida, Etapa etapa,
            Herramientas h, ProcesoNativo.Control control, Consumer<String> progreso) throws IOException {
        p.proyecto.comprobarDependencias(); control.comprobar();
        Path destino = salida.toAbsolutePath().normalize();
        if (Files.exists(destino, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Selecciona una carpeta nueva; el destino ya existe");
        if (destino.getParent() == null || !Files.isDirectory(destino.getParent())) throw new IOException("La carpeta padre no existe");
        Path tmp = Files.createTempDirectory(destino.getParent(), ".analizador-nativo-");
        boolean publicado = false;
        try {
            Files.writeString(tmp.resolve("programa.asm"), p.asm, StandardCharsets.UTF_8);
            Files.writeString(tmp.resolve("programa.ir"), p.atomica, StandardCharsets.UTF_8);
            Files.writeString(tmp.resolve("programa.tipada.ir"), p.tipada, StandardCharsets.UTF_8);
            if(p.optimizado) {
                Files.writeString(tmp.resolve("programa.original.ir"),p.atomicaOriginal,StandardCharsets.UTF_8);
                Files.writeString(tmp.resolve("programa.original.tipada.ir"),p.tipadaOriginal,StandardCharsets.UTF_8);
                Files.writeString(tmp.resolve("optimizacion.txt"),p.resumenOptimizacion+"\n",StandardCharsets.UTF_8);
            }
            Path objeto = tmp.resolve("programa" + p.destino.extensionObjeto);
            progreso.accept("Ensamblando");
            ejecutar(Arrays.asList(h.nasm, "-f", p.destino.formatoObjeto, tmp.resolve("programa.asm").toString(), "-o", objeto.toString()), tmp, h, control, progreso);
            progreso.accept("Ensamblado");
            if (etapa == Etapa.EJECUTABLE) {
                progreso.accept("Compilando runtime");
                String maquina = ejecutar(Arrays.asList(h.cc, "-dumpmachine"), tmp, h, control, progreso).stdout().trim();
                boolean win = p.destino == DestinoNativoIr.WINDOWS_X64;
                if (!maquina.startsWith("x86_64") || (win ? !(maquina.contains("mingw") || maquina.contains("windows")) : !maquina.contains("linux")))
                    throw new IOException("El compilador C no corresponde a " + p.destino.id + ": " + maquina);
                if (win) {
                    Path probe = tmp.resolve("verificar_ucrt.c");
                    Files.writeString(probe, "#include <_mingw.h>\n#ifndef _UCRT\n#error El destino Windows exige UCRT\n#endif\n");
                    ejecutar(Arrays.asList(h.cc, "-E", probe.toString(), "-o", tmp.resolve("verificar_ucrt.i").toString()), tmp, h, control, progreso);
                    Files.delete(probe); Files.delete(tmp.resolve("verificar_ucrt.i"));
                }
                Path runtime = tmp.resolve("runtime"); extraerRuntime(runtime);
                List<String> objetos = new ArrayList<>();
                for (String fuente : FUENTES) {
                    Path obj = runtime.resolve(fuente.replace(".c", p.destino.extensionObjeto));
                    List<String> comando = new ArrayList<>(Arrays.asList(h.cc, "-std=c11", "-Wall", "-Wextra", "-Werror", "-pedantic", "-O2", "-ffp-contract=off", "-fno-fast-math", "-I", runtime.resolve("include").toString(), "-c", runtime.resolve("src/" + fuente).toString(), "-o", obj.toString()));
                    ejecutar(comando, tmp, h, control, progreso); objetos.add(obj.toString());
                }
                Path biblioteca = runtime.resolve("libanalizador_runtime.a");
                List<String> archivo = new ArrayList<>(Arrays.asList(h.ar, "rcs", biblioteca.toString())); archivo.addAll(objetos);
                ejecutar(archivo, tmp, h, control, progreso);
                progreso.accept("Enlazando");
                List<String> enlace = new ArrayList<>(Arrays.asList(h.cc)); if (!win) enlace.add("-no-pie");
                enlace.addAll(Arrays.asList(objeto.toString(), biblioteca.toString(), "-lm", "-o", tmp.resolve(win ? "programa.exe" : "programa").toString()));
                ejecutar(enlace, tmp, h, control, progreso);
                Files.writeString(runtime.resolve("identidad-runtime.txt"), "target=" + p.destino.id + "\ncompiler_machine=" + maquina + "\ncrt=" + (win ? "UCRT" : "Linux") + "\n");
                progreso.accept("Enlazado");
            }
            Files.writeString(tmp.resolve("identidad-programa.txt"), p.identidad(), StandardCharsets.UTF_8);
            p.proyecto.comprobarDependencias();
            synchronized (control) {
                control.comprobar();
                // Sin REPLACE_EXISTING: nunca sustituye fuentes ni otro ejecutable.
                Files.move(tmp, destino);
                publicado = true;
            }
            return new Artefacto(destino, p, etapa);
        } finally { if (!publicado) borrar(tmp); }
    }
    private static ProcesoNativo.Resultado ejecutar(List<String> comando, Path cwd, Herramientas h,
            ProcesoNativo.Control control, Consumer<String> log) throws IOException {
        log.accept("$ " + String.join(" | ", comando));
        ProcesoNativo.Resultado r;
        try { r = ProcesoNativo.ejecutar(comando, cwd, new byte[0], h.plazo, control); }
        catch (IOException e) { throw new IOException(comando.get(0) + ": " + e.getMessage(), e); }
        if (!r.stdout().isEmpty()) log.accept(r.stdout()); if (!r.stderr().isEmpty()) log.accept(r.stderr());
        if (r.codigo != 0) throw new IOException("Falló " + comando.get(0) + " (código " + r.codigo + ")\n" + r.stdout() + r.stderr());
        return r;
    }
    static boolean hostWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"); }
    static boolean puedeEjecutar(DestinoNativoIr d) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch");
        return (arch.equals("amd64") || arch.equals("x86_64")) && (d == DestinoNativoIr.WINDOWS_X64 ? hostWindows() : os.equals("linux"));
    }
    static ProcesoNativo.Resultado ejecutarPrograma(Artefacto a, String entrada, int segundos, ProcesoNativo.Control c) throws IOException {
        comprobarEjecutable(a);
        return ProcesoNativo.ejecutar(Collections.singletonList(a.ejecutable.toString()), a.carpeta, entrada.getBytes(StandardCharsets.UTF_8), segundos, c);
    }
    static ProcesoNativo.Resultado ejecutarProgramaInteractivo(Artefacto a, ProcesoNativo.EntradaInteractiva entrada,
            int segundos, ProcesoNativo.Control c, Consumer<String> salida, Consumer<String> errores) throws IOException {
        comprobarEjecutable(a);
        return ProcesoNativo.ejecutarInteractivo(Collections.singletonList(a.ejecutable.toString()),a.carpeta,entrada,segundos,c,salida,errores);
    }
    private static void comprobarEjecutable(Artefacto a) throws IOException {
        a.programa.proyecto.comprobarDependencias();
        if (a.ejecutable == null || !Files.isRegularFile(a.ejecutable)) throw new IOException("No hay ejecutable enlazado");
        if (!puedeEjecutar(a.programa.destino)) throw new IOException("El destino no corresponde al sistema anfitrión; ejecuta el artefacto en " + a.programa.destino.id);
    }
    private static final String[] FUENTES = {"rt_console.c", "rt_float_java11.c", "rt_memory.c", "rt_platform.c", "rt_runtime.c"};
    private static final String[] CABECERAS = {"rt_console.h", "rt_platform.h", "rt_runtime.h", "rt_testing.h"};
    private static void extraerRuntime(Path destino) throws IOException {
        for (String f : FUENTES) extraer("runtime/nativo/src/" + f, destino.resolve("src/" + f));
        for (String f : CABECERAS) extraer("runtime/nativo/include/" + f, destino.resolve("include/" + f));
        for (String f : new String[]{"LICENSE", "ASSEMBLY_EXCEPTION", "ADDITIONAL_LICENSE_INFO", "procedencia.json"})
            extraer("runtime/terceros/openjdk11/" + f, destino.resolve("licencias/openjdk11/" + f));
    }
    private static void extraer(String recurso, Path destino) throws IOException {
        Files.createDirectories(destino.getParent());
        try (InputStream in = ConstructorNativo.class.getResourceAsStream("/" + recurso)) {
            if (in != null) { Files.copy(in, destino); return; }
        }
        Path raiz = raizDistribucion();
        if (raiz == null || !Files.isRegularFile(raiz.resolve(recurso))) throw new IOException("Falta un recurso del runtime: " + recurso + ". Reempaqueta el JAR.");
        Files.copy(raiz.resolve(recurso), destino);
    }
    private static Path raizDistribucion() {
        try {
            Path codigo = Paths.get(ConstructorNativo.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            if (Files.isRegularFile(codigo)) codigo = codigo.getParent();
            for (Path p : Arrays.asList(codigo, codigo.getParent(), Paths.get("").toAbsolutePath()))
                if (p != null && Files.isDirectory(p.resolve("runtime/nativo"))) return p;
        } catch (Exception ignored) { }
        return null;
    }
    private static void borrar(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> rutas = Files.walk(dir)) {
            for (Path p : (Iterable<Path>)rutas.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
}
