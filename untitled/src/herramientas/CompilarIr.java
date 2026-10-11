import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Entrada de consola IR, soporte H4 y programa NASM completo H5. */
public final class CompilarIr {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.exit(ejecutar(args, System.in, System.out, System.err));
    }
    static int ejecutar(String[] args, InputStream in, OutputStream out, PrintStream err) {
        try {
            if (args.length == 0) throw new IllegalArgumentException("Uso: CompilarIr archivo [--entrada Clase] [--ir archivo.ir] [--ir-tipada archivo.ir] [--target linux-x64|windows-x64] [--soporte-nativo archivo.asm] [--asm archivo.asm] [--optimizar] [--ir-original archivo.ir] [--ir-tipada-original archivo.ir] [--ejecutar] [--max-pasos N]");
            Path fuente = Path.of(args[0]); String entrada = null; Path exportar = null, exportarTipada = null, soporte = null, asm = null; DestinoNativoIr destino = DestinoNativoIr.LINUX_X64; boolean optimizar=false; Path originalFisica=null,originalTipada=null; boolean ejecutar = false; long limite = 0;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--optimizar": optimizar=true;break;
                    case "--ir-original": originalFisica=Path.of(args[++i]);break;
                    case "--ir-tipada-original": originalTipada=Path.of(args[++i]);break;
                    case "--target": destino = DestinoNativoIr.seleccionar(args[++i]); break;
                    case "--asm": asm = Path.of(args[++i]); break;
                    case "--entrada": entrada = args[++i]; break;
                    case "--ir": case "--ir-atomica": exportar = Path.of(args[++i]); break;
                    case "--ir-tipada": exportarTipada = Path.of(args[++i]); break;
                    case "--soporte-nativo": soporte = Path.of(args[++i]); break;
                    case "--ejecutar": ejecutar = true; break;
                    case "--max-pasos": limite = Long.parseLong(args[++i]); if (limite < 0) throw new IllegalArgumentException("Presupuesto negativo"); break;
                    default: throw new IllegalArgumentException("Opción desconocida: " + args[i]);
                }
            }
            List<Main.ResultadoAnalisis> unidades = Main.analizarProyecto(fuente);
            for (Main.ResultadoAnalisis u : unidades) if (u.resultadoSemantico == null || !u.resultadoSemantico.esExitoso()) {
                err.println(u.archivo + ": " + u.estadoDiagnosticos());
                for (compiladorErrores e : u.diagnosticos()) err.println(e);
                for (String e : u.erroresImport) err.println(e);
                return 1;
            }
            ResultadoGeneracionIr r = new GeneradorIr().generar(unidades, entrada);
            if (!r.esExitoso()) { for (String e : r.errores) err.println(e); return 1; }
            ModuloIr original=r.modulo;
            ModuloIr usado=optimizar?new OptimizadorIr().optimizar(original).optimizado:original;
            ModuloAtomicoIr atomico = new LinealizadorIr().transformar(usado, destino);
            for (Path salida : Arrays.asList(exportar, exportarTipada, soporte, asm, originalFisica, originalTipada)) if (salida != null)
                for (Main.ResultadoAnalisis u : unidades)
                    if (salida.toAbsolutePath().normalize().equals(u.archivo.toAbsolutePath().normalize())
                            || Files.exists(salida) && Files.isSameFile(salida, u.archivo))
                        throw new IllegalArgumentException("La salida generada no puede sobrescribir una unidad fuente");
            List<Path> salidas = new ArrayList<>();
            for (Path salida : Arrays.asList(exportar, exportarTipada, soporte, asm, originalFisica, originalTipada)) if (salida != null) {
                for (Path previa : salidas) if (salida.toAbsolutePath().normalize().equals(previa.toAbsolutePath().normalize())
                        || Files.exists(salida) && Files.exists(previa) && Files.isSameFile(salida, previa))
                    throw new IllegalArgumentException("Las salidas generadas deben ser archivos distintos");
                salidas.add(salida);
            }
            String programaAsm = asm == null ? null : new GeneradorEnsamblador(optimizar).generar(atomico);
            String textoOriginal=originalFisica==null?null:new ImpresorAtomicoIr().imprimir(new LinealizadorIr().transformar(original,destino));
            String textoTipadoOriginal=originalTipada==null?null:new ImpresorIr().imprimir(original);
            if(originalFisica!=null)Files.writeString(originalFisica,textoOriginal);
            if(originalTipada!=null)Files.writeString(originalTipada,textoTipadoOriginal);
            if (asm != null) Files.writeString(asm, programaAsm);
            if (exportar != null) Files.writeString(exportar, new ImpresorAtomicoIr().imprimir(atomico));
            if (exportarTipada != null) Files.writeString(exportarTipada, new ImpresorIr().imprimir(usado));
            if (soporte != null) Files.writeString(soporte, new SoporteNativoIr().nasm(atomico));
            if (ejecutar) new InterpreteAtomicoIr().ejecutar(atomico, new ConsolaReferencia(in, out), limite);
            else if (exportar == null && exportarTipada == null && soporte == null && asm == null && originalFisica == null && originalTipada == null) { out.write(new ImpresorAtomicoIr().imprimir(atomico).getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush(); }
            return 0;
        } catch (ErrorEjecucionIr e) { err.println(e.diagnostico()); return 1; }
        catch (InterpreteIr.LimitePruebaIr e) { err.println(e.getMessage()); return 2; }
        catch (ArrayIndexOutOfBoundsException e) { err.println("Falta un valor para una opción"); return 1; }
        catch (IllegalArgumentException | IOException e) { err.println(e.getMessage()); return 1; }
    }
}
