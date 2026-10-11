import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Supervisión de procesos sin shell: tres canales independientes, plazo y cancelación. */
final class ProcesoNativo {
    static final int LIMITE_SALIDA = 1024 * 1024;
    static final class Control {
        private volatile boolean cancelado;
        private volatile Process proceso;
        private final Set<ProcessHandle> hijos = ConcurrentHashMap.newKeySet();
        void rastrear(Process p) { p.descendants().forEach(hijos::add); }
        synchronized void cancelar() { cancelado = true; detener(proceso); hijos.forEach(h -> { if (h.isAlive()) h.destroyForcibly(); }); }
        void terminar(Process p) {
            rastrear(p); detener(p); hijos.forEach(h -> { if (h.isAlive()) h.destroyForcibly(); });
            // Espera solo en el trabajador para soltar los archivos abiertos antes de limpiar.
            boolean interrumpido = Thread.interrupted();
            long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            try {
                p.waitFor(2, TimeUnit.SECONDS);
                for (ProcessHandle h : hijos) if (h.isAlive()) {
                    try { h.onExit().get(Math.max(1, fin-System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (ExecutionException | TimeoutException ignored) { }
                }
            } catch (InterruptedException e) { interrumpido = true; }
            finally { if (interrumpido) Thread.currentThread().interrupt(); }
        }
        void comprobar() throws IOException {
            if (cancelado || Thread.currentThread().isInterrupted()) throw new IOException("Operación cancelada");
        }
        synchronized void registrar(Process p) throws IOException {
            proceso = p;
            if (cancelado) { detener(p); comprobar(); }
        }
    }
    /** Cola acotada: la GUI nunca escribe en un pipe ni espera a que el hijo lea. */
    static final class EntradaInteractiva {
        private final ArrayDeque<byte[]> pendientes = new ArrayDeque<>();
        private int bytesPendientes;
        private boolean cerrada, terminada;
        synchronized boolean estaAbierta() { return !cerrada && !terminada; }
        synchronized void enviar(String texto) throws IOException {
            if (!estaAbierta()) throw new IOException("La entrada del programa está cerrada");
            byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > LIMITE_SALIDA - bytesPendientes)
                throw new IOException("Entrada pendiente excede el límite de 1 MiB; espera a que el programa lea");
            if (bytes.length == 0) return;
            pendientes.add(bytes); bytesPendientes += bytes.length; notifyAll();
        }
        synchronized void cerrar() { cerrada = true; notifyAll(); }
        synchronized void terminar() {
            terminada = true; pendientes.clear(); bytesPendientes = 0; notifyAll();
        }
        private synchronized byte[] siguiente() throws InterruptedException {
            while (pendientes.isEmpty() && !cerrada && !terminada) wait();
            if (terminada || pendientes.isEmpty()) return null;
            byte[] bytes = pendientes.remove(); bytesPendientes -= bytes.length; return bytes;
        }
        private void escribir(OutputStream canal) {
            try (OutputStream out = canal) {
                byte[] bytes;
                while ((bytes = siguiente()) != null) { out.write(bytes); out.flush(); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException e) { /* El hijo puede cerrar stdin antes de terminar. */ }
            finally { terminar(); }
        }
    }
    static final class Resultado {
        final int codigo;
        final byte[] salida, errores;
        Resultado(int codigo, byte[] salida, byte[] errores) {
            this.codigo = codigo; this.salida = salida; this.errores = errores;
        }
        String stdout() { return new String(salida, StandardCharsets.UTF_8); }
        String stderr() { return new String(errores, StandardCharsets.UTF_8); }
    }
    static final class Fallo extends IOException {
        final Resultado parcial;
        Fallo(IOException causa, byte[] out, byte[] err) { super(causa.getMessage(), causa); parcial = new Resultado(-1,out,err); }
    }
    private static final class Captura {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        synchronized void agregar(byte[] bloque, int n) throws IOException {
            if (bytes.size() + n > LIMITE_SALIDA) throw new IOException("Salida excede el límite de 1 MiB por canal");
            bytes.write(bloque, 0, n);
        }
        synchronized byte[] contenido() { return bytes.toByteArray(); }
    }
    static Resultado ejecutar(List<String> comando, Path directorio, byte[] entrada,
            int segundos, Control control) throws IOException {
        return ejecutar(comando,directorio,entrada,null,segundos,control,null,null);
    }
    static Resultado ejecutarInteractivo(List<String> comando, Path directorio, EntradaInteractiva entrada,
            int segundos, Control control, Consumer<String> salida, Consumer<String> errores) throws IOException {
        Objects.requireNonNull(entrada,"entrada");
        try { return ejecutar(comando,directorio,null,entrada,segundos,control,salida,errores); }
        finally { entrada.terminar(); }
    }
    private static Resultado ejecutar(List<String> comando, Path directorio, byte[] entrada,
            EntradaInteractiva interactiva, int segundos, Control control,
            Consumer<String> alSalir, Consumer<String> alError) throws IOException {
        if (segundos < 1) throw new IllegalArgumentException("Plazo inválido");
        control.comprobar();
        Process p = new ProcessBuilder(new ArrayList<>(comando)).directory(directorio.toFile()).start();
        ExecutorService canales = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "canal-nativo"); t.setDaemon(true); return t;
        });
        AtomicReference<IOException> errorCanal = new AtomicReference<>();
        Captura salida = new Captura(), errores = new Captura();
        try {
            control.registrar(p);
            Future<byte[]> stdout = canales.submit(() -> leer(p.getInputStream(), errorCanal, salida, alSalir));
            Future<byte[]> stderr = canales.submit(() -> leer(p.getErrorStream(), errorCanal, errores, alError));
            Future<?> stdin = canales.submit(() -> {
                if (interactiva != null) { interactiva.escribir(p.getOutputStream()); return; }
                try (OutputStream out = p.getOutputStream()) { out.write(entrada); }
                catch (IOException e) { /* El hijo puede cerrar stdin anticipadamente. */ }
            });
            long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(segundos);
            while (true) {
                control.rastrear(p);
                if (p.waitFor(50, TimeUnit.MILLISECONDS)) break;
                control.comprobar();
                if (errorCanal.get() != null) throw errorCanal.get();
                if (System.nanoTime() >= fin) throw new IOException("Tiempo límite excedido (" + segundos + " s)");
            }
            control.comprobar();
            // Incluye el drenaje en el plazo: un descendiente puede mantener los pipes abiertos.
            byte[] out = obtener(stdout, fin), err = obtener(stderr, fin);
            stdin.cancel(true);
            if (errorCanal.get() != null) throw errorCanal.get();
            return new Resultado(p.exitValue(), out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new Fallo(new IOException("Operación cancelada", e), salida.contenido(), errores.contenido());
        } catch (IOException e) {
            throw new Fallo(e, salida.contenido(), errores.contenido());
        } finally {
            if (interactiva != null) interactiva.terminar();
            control.terminar(p);
            try { p.getInputStream().close(); } catch (IOException ignored) { }
            try { p.getErrorStream().close(); } catch (IOException ignored) { }
            try { p.getOutputStream().close(); } catch (IOException ignored) { }
            canales.shutdownNow();
        }
    }
    private static byte[] obtener(Future<byte[]> canal, long fin) throws IOException, InterruptedException {
        try { return canal.get(Math.max(1, fin - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (ExecutionException e) { throw new IOException(e.getCause().getMessage(), e.getCause()); }
        catch (TimeoutException e) { throw new IOException("Tiempo límite excedido al drenar la salida", e); }
    }
    private static byte[] leer(InputStream in, AtomicReference<IOException> error, Captura bytes,
            Consumer<String> alLeer) throws IOException {
        try (InputStream canal = in) {
            if (alLeer == null) {
                byte[] bloque = new byte[8192]; int n;
                while ((n = canal.read(bloque)) != -1) bytes.agregar(bloque, n);
            } else {
                // El decoder conserva secuencias UTF-8 que llegan en varios bloques.
                InputStream observado = new FilterInputStream(canal) {
                    public int read(byte[] b,int offset,int longitud) throws IOException {
                        int n = this.in.read(b,offset,longitud);
                        if (n > 0) {
                            if (offset == 0) bytes.agregar(b,n);
                            else bytes.agregar(Arrays.copyOfRange(b,offset,offset+n),n);
                        }
                        return n;
                    }
                    public int read() throws IOException {
                        int n = this.in.read(); if (n >= 0) bytes.agregar(new byte[]{(byte)n},1); return n;
                    }
                };
                try (Reader lector = new InputStreamReader(observado,StandardCharsets.UTF_8)) {
                    char[] bloque = new char[2048]; int n;
                    while ((n = lector.read(bloque)) != -1) alLeer.accept(new String(bloque,0,n));
                }
            }
            return bytes.contenido();
        } catch (IOException e) { error.compareAndSet(null, e); throw e; }
    }
    private static void detener(Process p) {
        if (p == null) return;
        p.descendants().forEach(h -> { if (h.isAlive()) h.destroyForcibly(); });
        if (p.isAlive()) p.destroyForcibly();
    }
}
