import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Instantánea del análisis; nunca vuelve a compilar un archivo distinto del editor analizado. */
final class ProyectoNativo {
    final VisorIr.Resultado ir;
    final Map<Path, String> fuentes;
    final Path principal;
    final List<String> entradas;
    ProyectoNativo(VisorIr.Resultado ir, List<Main.ResultadoAnalisis> unidades, Path principal) {
        this.ir = ir; this.principal = principal.toAbsolutePath().normalize();
        Map<Path, String> textos = new LinkedHashMap<>();
        for (Main.ResultadoAnalisis u : unidades) textos.put(u.archivo.toAbsolutePath().normalize(), u.source);
        fuentes = Collections.unmodifiableMap(textos);
        List<String> candidatas = new ArrayList<>();
        if (ir.modulo != null) for (FuncionIr f : ir.modulo.funciones)
            if (SeleccionEntradaIr.esCandidata(f.firma)) candidatas.add(f.firma.propietaria);
        entradas = Collections.unmodifiableList(candidatas);
    }
    static final class DependenciaDesactualizada extends IOException {
        DependenciaDesactualizada(Path archivo, IOException causa) { super("Cambió o no se puede leer una dependencia: " + archivo + ". Vuelve a analizar.", causa); }
    }
    void comprobarDependencias() throws IOException {
        for (Map.Entry<Path,String> u : fuentes.entrySet()) if (!u.getKey().equals(principal)) {
            String actual;
            try { actual = Files.readString(u.getKey(), StandardCharsets.UTF_8); }
            catch (IOException e) { throw new DependenciaDesactualizada(u.getKey(), e); }
            if (!actual.equals(u.getValue())) throw new DependenciaDesactualizada(u.getKey(), null);
        }
    }
    Preparado generar(DestinoNativoIr destino, String entrada) throws IOException { return generar(destino,entrada,false); }
    Preparado generar(DestinoNativoIr destino, String entrada, boolean optimizar) throws IOException {
        comprobarDependencias();
        if (!ir.esExitoso() || ir.modulo == null) throw new IOException("El proyecto no tiene IR válida");
        List<FirmaIr> firmas = new ArrayList<>();
        for (FuncionIr f : ir.modulo.funciones) firmas.add(f.firma);
        FirmaIr main = SeleccionEntradaIr.elegir(firmas, entrada);
        ModuloIr modulo = new ModuloIr(ir.modulo.nombre, ir.modulo.clases, ir.modulo.funciones, main);
        String tipadaOriginal=new ImpresorIr().imprimir(modulo);
        ModuloAtomicoIr fisicaOriginal=new LinealizadorIr().transformar(modulo,destino);
        String atomicaOriginal=new ImpresorAtomicoIr().imprimir(fisicaOriginal);
        String resumen="Sin optimización";
        if(optimizar) { OptimizadorIr.Resultado r=new OptimizadorIr().optimizar(modulo);modulo=r.optimizado;resumen=r.resumen(); }
        ModuloAtomicoIr fisica = optimizar?new LinealizadorIr().transformar(modulo, destino):fisicaOriginal;
        if(optimizar) {
            int antes=0,despues=0,registros=0,frameAntes=0,frameDespues=0;
            for(FuncionAtomicaIr f:fisicaOriginal.funciones)antes+=f.codigo.size();
            for(FuncionAtomicaIr f:fisica.funciones) { despues+=f.codigo.size();registros+=new AsignadorRegistrosNasm().asignar(f).size(); }
            for(LayoutProgramaIr.Marco f:fisicaOriginal.layout.marcos.values())frameAntes+=f.tamano;
            for(LayoutProgramaIr.Marco f:fisica.layout.marcos.values())frameDespues+=f.tamano;
            resumen+="\nCuádruplos: "+antes+" → "+despues+"\nTemporales asignados a R8/R9: "+registros
                    +"\nSuma de marcos reservados: "+frameAntes+" → "+frameDespues+" bytes\nLos registros reutilizan temporales sin alterar los offsets de la IR física.";
        }
        return new Preparado(this, destino, main.propietaria, new GeneradorEnsamblador(optimizar).generar(fisica),
                new ImpresorAtomicoIr().imprimir(fisica), new ImpresorIr().imprimir(modulo),atomicaOriginal,tipadaOriginal,resumen,optimizar,PresentacionDidacticaIr.desdeTipada(modulo));
    }
    static String huella(String texto) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(); for (byte b : hash) s.append(String.format("%02x", b & 255)); return s.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static final class Preparado {
        final ProyectoNativo proyecto;
        final DestinoNativoIr destino;
        final String entrada, asm, atomica, tipada, atomicaOriginal, tipadaOriginal, resumenOptimizacion;
        final boolean optimizado;
        final PresentacionDidacticaIr.Resultado didactica;
        Preparado(ProyectoNativo proyecto, DestinoNativoIr destino, String entrada, String asm, String atomica, String tipada, String atomicaOriginal, String tipadaOriginal, String resumen, boolean optimizado, PresentacionDidacticaIr.Resultado didactica) {
            this.proyecto = proyecto; this.destino = destino; this.entrada = entrada;
            this.asm = asm; this.atomica = atomica; this.tipada = tipada;
            this.atomicaOriginal=atomicaOriginal;this.tipadaOriginal=tipadaOriginal;this.resumenOptimizacion=resumen;this.optimizado=optimizado;this.didactica=didactica;
        }
        void exportar(Path archivo) throws IOException {
            exportar(archivo, false);
        }
        void exportar(Path archivo, boolean irDestino) throws IOException {
            exportar(archivo, irDestino, new ProcesoNativo.Control());
        }
        void exportar(Path archivo, boolean irDestino, ProcesoNativo.Control control) throws IOException {
            proyecto.comprobarDependencias(); proyecto.ir.validarDestino(archivo); control.comprobar();
            Path destino = archivo.toAbsolutePath().normalize();
            Path temporal = Files.createTempFile(destino.getParent(), ".analizador-exportar-", ".tmp");
            try {
                Files.writeString(temporal, irDestino ? atomica : asm, StandardCharsets.UTF_8);
                proyecto.comprobarDependencias();
                synchronized (control) {
                    control.comprobar(); proyecto.ir.validarDestino(destino);
                    Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporal); }
        }
        String identidad() {
            StringBuilder s = new StringBuilder("target=" + destino.id + "\nentrada=" + entrada + "\noptimizacion=" + (optimizado ? "H7" : "ninguna") + "\nasm.sha256=" + huella(asm) + "\n");
            for (Map.Entry<Path,String> u : proyecto.fuentes.entrySet())
                s.append("fuente.sha256=").append(huella(u.getValue())).append(" ").append(u.getKey()).append('\n');
            return s.append("ejecucion=pendiente\n").toString();
        }
    }
}
