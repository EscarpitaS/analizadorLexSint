import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Auditoría de la frontera física y oráculos independientes para ambas representaciones. */
public final class PruebasConsistenciaIr {
    private static final OrigenIr O = new OrigenIr("auditoria.txt", 3, 5);
    private static int comprobaciones;
    private static Path fuente;
    public static void main(String[] args) { ejecutar(); }
    static void ejecutar() {
        comprobaciones = 0;
        try {
            fuente = Files.createTempFile("consistencia-ir-", ".txt");
            despacho(); tiposEIdentidad(); memoriaVirtual(); datosNativos(); presentacion(); ordenYAsignaciones(); aritmetica();
            System.out.println("OK: " + comprobaciones + " comprobaciones de consistencia IR y rechazo de módulos adulterados.");
        } catch (IOException e) { throw new AssertionError(e); }
        finally { if (fuente != null) try { Files.delete(fuente); } catch (IOException e) { throw new AssertionError(e); } }
    }
    private static void exigir(boolean valor, String mensaje) { comprobaciones++; if (!valor) throw new AssertionError(mensaje); }
    private static ModuloIr generar(String texto) throws IOException {
        ResultadoGeneracionIr r = new GeneradorIr().generar(Main.analizarProyecto(fuente, texto, false), null);
        exigir(r.esExitoso(), "Fuente válida rechazada: " + r.errores + "\n" + texto); return r.modulo;
    }
    private static String programa(String miembros, String cuerpo) {
        return "import runtime.Consola; { public class Main { " + miembros
                + " public main() { Consola c=new Consola(); " + cuerpo + " } } }";
    }
    private static String correr(ModuloIr m, boolean atomica) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsolaReferencia c = new ConsolaReferencia(new ByteArrayInputStream(new byte[0]), out);
        if (atomica) new InterpreteAtomicoIr().ejecutar(new LinealizadorIr().transformar(m), c, 1000000);
        else new InterpreteIr().ejecutar(m, c, 1000000);
        return out.toString(StandardCharsets.UTF_8);
    }
    private static String correr(ModuloAtomicoIr m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new InterpreteAtomicoIr().ejecutar(m, new ConsolaReferencia(new ByteArrayInputStream(new byte[0]), out), 1000000);
        return out.toString(StandardCharsets.UTF_8);
    }
    private static void salida(String texto, String esperado) throws IOException {
        ModuloIr m = generar(texto);
        exigir(correr(m, false).equals(esperado), "La IR tipada no cumple el oráculo: " + texto);
        exigir(correr(m, true).equals(esperado), "La IR atómica no cumple el oráculo: " + texto);
        String tipada = new ImpresorIr().imprimir(m), fisica = new ImpresorAtomicoIr().imprimir(new LinealizadorIr().transformar(m));
        PresentacionDidacticaIr.desdeTipada(m);
        exigir(tipada.equals(new ImpresorIr().imprimir(m)) && fisica.equals(new ImpresorAtomicoIr().imprimir(new LinealizadorIr().transformar(m))),
                "La proyección de consulta modificó el módulo");
    }
    private static FuncionAtomicaIr principal(ModuloAtomicoIr m) {
        return m.funciones.stream().filter(f -> f.firma.id().equals(m.entrada.id())).findFirst().orElseThrow();
    }
    private static ModuloAtomicoIr codigo(ModuloAtomicoIr m, FuncionAtomicaIr objetivo, List<CuadruploIr> codigo) {
        List<FuncionAtomicaIr> fs = new ArrayList<>();
        for (FuncionAtomicaIr f : m.funciones) fs.add(f == objetivo ? new FuncionAtomicaIr(f.firma, f.receptor, f.parametros, codigo) : f);
        return new ModuloAtomicoIr(m.nombre, m.entrada, m.layout, fs);
    }
    private static ModuloAtomicoIr clase(ModuloAtomicoIr m, LayoutProgramaIr.Clase clase) {
        Map<String, LayoutProgramaIr.Clase> cs = new LinkedHashMap<>(m.layout.clases); cs.put(clase.nombre, clase);
        return new ModuloAtomicoIr(m.nombre, m.entrada, new LayoutProgramaIr(cs, m.layout.marcos), m.funciones);
    }
    private static void rechaza(ModuloAtomicoIr m, String codigo) {
        List<String> errores = new VerificadorAtomicoIr().verificar(m);
        exigir(errores.stream().anyMatch(e -> e.contains(codigo)), "Falta rechazo " + codigo + ": " + errores);
        try { new ImpresorAtomicoIr().imprimir(m); throw new AssertionError("Se exportó IR inválida"); }
        catch (IllegalArgumentException e) { comprobaciones++; }
        try { new DatosNativosIr(m); throw new AssertionError("Se generaron datos para IR inválida"); }
        catch (IllegalArgumentException e) { comprobaciones++; }
        try { correr(m); throw new AssertionError("Se ejecutó IR inválida"); }
        catch (IllegalArgumentException e) { comprobaciones++; }
    }
    private static ModuloAtomicoIr ejemploDespacho() throws IOException {
        return new LinealizadorIr().transformar(generar("import runtime.Consola; { "
                + "public class A { public int valor() { return 1; } } "
                + "public class B extends A { public int valor() { return 2; } public int extra() { return 3; } } "
                + "public class Main { public main() { Consola c=new Consola(); A a=new A(); A b=new B(); "
                + "c.imprimirLinea(a.valor()); c.imprimirLinea(b.valor()); } } }"));
    }
    private static void despacho() throws IOException {
        ModuloAtomicoIr m = ejemploDespacho(); exigir(correr(m).equals("1\n2\n"), "Despacho original incorrecto");
        List<FuncionAtomicaIr> fs = new ArrayList<>(m.funciones); Collections.reverse(fs);
        ModuloAtomicoIr funcionesReordenadas = new ModuloAtomicoIr(m.nombre, m.entrada, m.layout, fs);
        exigir(new VerificadorAtomicoIr().verificar(funcionesReordenadas).isEmpty() && correr(funcionesReordenadas).equals("1\n2\n"),
                "Reordenar cuerpos de funciones invalidó slots ya resueltos");
        LayoutProgramaIr.Clase b = m.layout.clases.get("B"), a = m.layout.clases.get("A");
        List<FirmaIr> virtuales = new ArrayList<>(b.virtuales); virtuales.set(0, a.virtuales.get(0));
        rechaza(clase(m, new LayoutProgramaIr.Clase(b.nombre, b.base, false, b.tamano, b.campos, b.tiposCampos, virtuales, b.inicializador)), "TABLA_VIRTUAL_INCONSISTENTE");
        virtuales = new ArrayList<>(b.virtuales); virtuales.remove(virtuales.size() - 1);
        rechaza(clase(m, new LayoutProgramaIr.Clase(b.nombre, b.base, false, b.tamano, b.campos, b.tiposCampos, virtuales, b.inicializador)), "TABLA_VIRTUAL_INCONSISTENTE");
        // Reducir la visibilidad de un override tampoco es válido en la frontera física.
        fs = new ArrayList<>();
        for (FuncionAtomicaIr f : m.funciones) {
            FirmaIr firma = f.firma;
            if (firma.propietaria.equals("B") && firma.nombre.equals("valor"))
                firma = new FirmaIr(firma.propietaria, firma.nombre, firma.retorno, FirmaIr.Visibilidad.PRIVADA, firma.parametros);
            fs.add(new FuncionAtomicaIr(firma, f.receptor, f.parametros, f.codigo));
        }
        virtuales = new ArrayList<>(b.virtuales); virtuales.set(0, a.virtuales.get(0));
        ModuloAtomicoIr visibilidad = clase(m, new LayoutProgramaIr.Clase(b.nombre, b.base, false, b.tamano, b.campos, b.tiposCampos, virtuales, b.inicializador));
        rechaza(new ModuloAtomicoIr(m.nombre, m.entrada, visibilidad.layout, fs), "SOBRESCRITURA_INVALIDA");
        FuncionAtomicaIr main = principal(m); List<CuadruploIr> qs = new ArrayList<>(main.codigo); ValorIr puntero = null;
        for (int i = 0; i < qs.size(); i++) {
            CuadruploIr q = qs.get(i);
            if (q.operacion == CuadruploIr.Operacion.CALL_INDIRECT) {
                if (puntero == null) puntero = q.fuente1.llamada.puntero;
                else { qs.set(i, new CuadruploIr(q.operacion, OperandoAtomico.llamada(LlamadaAtomica.usuario(q.fuente1.llamada.firma, puntero)), q.fuente2, q.destino, q.origen)); break; }
            }
        }
        rechaza(codigo(m, main, qs), "RECEPTOR_VIRTUAL_INCONSISTENTE");
        // Copiar explícitamente una referencia preserva el objeto y es un receptor válido.
        qs = new ArrayList<>(main.codigo); ValorIr aliasReceptor = ValorIr.temporal("audReceptor", TipoIr.objeto("A"));
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.CALL_INDIRECT) {
            CuadruploIr parametro = qs.get(i - 1);
            exigir(parametro.operacion == CuadruploIr.Operacion.PARAM, "Fixture sin receptor");
            qs.set(i - 1, new CuadruploIr(CuadruploIr.Operacion.PARAM, OperandoAtomico.valor(aliasReceptor), parametro.fuente2, null, parametro.origen));
            qs.add(i - 1, new CuadruploIr(CuadruploIr.Operacion.COPY, parametro.fuente1, null, OperandoAtomico.valor(aliasReceptor), O)); break;
        }
        ModuloAtomicoIr receptorCopiado = agregarValor(codigo(m, main, qs), main, aliasReceptor);
        exigir(new VerificadorAtomicoIr().verificar(receptorCopiado).isEmpty(), "Un alias explícito del receptor perdió su identidad");
        exigir(correr(receptorCopiado).equals("1\n2\n"), "Copiar el receptor cambió el despacho");
        // Una definición dominante puede estar listada después de su uso en otro bloque.
        qs = new ArrayList<>(main.codigo); int cargaTabla = -1;
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.CALL_INDIRECT) { cargaTabla = i - 3; break; }
        exigir(qs.get(cargaTabla).operacion == CuadruploIr.Operacion.LOAD && qs.get(cargaTabla + 1).operacion == CuadruploIr.Operacion.LOAD, "Fixture de carga virtual incorrecto");
        List<CuadruploIr> reordenada = new ArrayList<>(qs.subList(0, cargaTabla));
        reordenada.add(new CuadruploIr(CuadruploIr.Operacion.JUMP, OperandoAtomico.etiqueta("AudDef"), null, null, O));
        reordenada.add(new CuadruploIr(CuadruploIr.Operacion.LABEL, null, null, OperandoAtomico.etiqueta("AudUso"), O));
        reordenada.addAll(qs.subList(cargaTabla + 2, qs.size()));
        reordenada.add(new CuadruploIr(CuadruploIr.Operacion.LABEL, null, null, OperandoAtomico.etiqueta("AudDef"), O));
        reordenada.addAll(qs.subList(cargaTabla, cargaTabla + 2));
        reordenada.add(new CuadruploIr(CuadruploIr.Operacion.JUMP, OperandoAtomico.etiqueta("AudUso"), null, null, O));
        ModuloAtomicoIr ordenDistinto = codigo(m, main, reordenada);
        exigir(new VerificadorAtomicoIr().verificar(ordenDistinto).isEmpty(), "La procedencia depende del orden textual de los bloques");
        exigir(correr(ordenDistinto).equals("1\n2\n"), "Reordenar bloques cambió el despacho");
        // NASM usa nombres de etiqueta globales; repetirlos entre funciones no es válido.
        String etiqueta = main.codigo.stream().filter(q -> q.operacion == CuadruploIr.Operacion.LABEL).findFirst().orElseThrow().destino.etiqueta;
        FuncionAtomicaIr otra = m.funciones.stream().filter(f -> f.firma.propietaria.equals("A")).findFirst().orElseThrow();
        qs = new ArrayList<>(otra.codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.LABEL) {
            qs.set(i, new CuadruploIr(CuadruploIr.Operacion.LABEL, null, null, OperandoAtomico.etiqueta(etiqueta), O)); break;
        }
        rechaza(codigo(m, otra, qs), "ETIQUETA_GLOBAL_DUPLICADA");
    }
    private static void tiposEIdentidad() throws IOException {
        ModuloAtomicoIr m = new LinealizadorIr().transformar(generar(programa("", "int n=4; float f=n; c.imprimirLinea(f);")));
        FuncionAtomicaIr main = principal(m); List<CuadruploIr> qs = new ArrayList<>(main.codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.INT_A_FLOAT) {
            CuadruploIr q = qs.get(i); ValorIr original = q.fuente1.valor;
            qs.set(i, new CuadruploIr(q.operacion, OperandoAtomico.valor(ValorIr.parametro(original.id, original.tipo)), q.fuente2, q.destino, q.origen)); break;
        }
        rechaza(codigo(m, main, qs), "VALOR_INCONSISTENTE");
        // Clases desconocidas anidadas en un parámetro que nunca se usa.
        FirmaIr firma = new FirmaIr("Main", "f", TipoIr.VOID, FirmaIr.Visibilidad.PRIVADA, List.of(TipoIr.lista(TipoIr.objeto("Ausente"))));
        ValorIr yo = ValorIr.parametro("this", TipoIr.objeto("Main")), p = ValorIr.parametro("p", firma.parametros.get(0));
        LayoutProgramaIr.ConstructorMarco marco = new LayoutProgramaIr.ConstructorMarco(); marco.valor(yo); marco.valor(p);
        List<LugarArgumentoIr> lugares = ConvencionLlamadasIr.argumentos(List.of(yo.tipo, p.tipo), true);
        qs = List.of(new CuadruploIr(CuadruploIr.Operacion.READ_PARAM, OperandoAtomico.argumento(lugares.get(0)), null, OperandoAtomico.valor(yo), O),
                new CuadruploIr(CuadruploIr.Operacion.READ_PARAM, OperandoAtomico.argumento(lugares.get(1)), null, OperandoAtomico.valor(p), O),
                new CuadruploIr(CuadruploIr.Operacion.RETURN, null, null, null, O));
        m = new ModuloAtomicoIr("Tipos", null, new LayoutProgramaIr(Map.of("Main", new LayoutProgramaIr.Clase("Main", null, false, 16, Map.of(), Map.of(), List.of())),
                Map.of(firma.id(), marco.construir())), List.of(new FuncionAtomicaIr(firma, yo, List.of(p), qs)));
        rechaza(m, "CLASE_INEXISTENTE");
    }
    private static ModuloAtomicoIr aliasPuntero(ModuloAtomicoIr m) {
        FuncionAtomicaIr main = principal(m); List<CuadruploIr> qs = new ArrayList<>(main.codigo); ValorIr alias = ValorIr.temporal("audAlias", TipoIr.direccion(TipoIr.INT));
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.CALL_INDIRECT) {
            CuadruploIr q = qs.get(i); ValorIr puntero = q.fuente1.llamada.puntero;
            qs.set(i, new CuadruploIr(q.operacion, OperandoAtomico.llamada(LlamadaAtomica.usuario(q.fuente1.llamada.firma, alias)), q.fuente2, q.destino, q.origen));
            int definicion = -1;
            for (int j = 0; j < i; j++) if (qs.get(j).destino != null && qs.get(j).destino.clase == OperandoAtomico.Clase.VALOR
                    && qs.get(j).destino.valor.id.equals(puntero.id)) definicion = j;
            qs.add(definicion + 1, new CuadruploIr(CuadruploIr.Operacion.COPY, OperandoAtomico.valor(puntero), null, OperandoAtomico.valor(alias), O)); break;
        }
        return agregarValor(codigo(m, main, qs), main, alias);
    }
    private static ModuloAtomicoIr agregarValor(ModuloAtomicoIr m, FuncionAtomicaIr funcion, ValorIr valor) {
        LayoutProgramaIr.Marco anterior = m.layout.marcos.get(funcion.firma.id());
        Map<String, Integer> offsets = new LinkedHashMap<>(anterior.valores); offsets.put(valor.id, -anterior.bytesValores - 8);
        Map<String, TipoIr> tipos = new LinkedHashMap<>(anterior.tiposValores); tipos.put(valor.id, valor.tipo);
        Map<String, LayoutProgramaIr.Marco> marcos = new LinkedHashMap<>(m.layout.marcos);
        marcos.put(funcion.firma.id(), new LayoutProgramaIr.Marco(offsets, anterior.locales, tipos, anterior.tiposLocales, anterior.bytesValores + 8, anterior.maxArgumentos));
        return new ModuloAtomicoIr(m.nombre, m.entrada, new LayoutProgramaIr(m.layout.clases, marcos), m.funciones);
    }
    private static void memoriaVirtual() throws IOException {
        ModuloAtomicoIr original = ejemploDespacho(); FuncionAtomicaIr principal = principal(original);
        List<CuadruploIr> adulterada = new ArrayList<>(principal.codigo);
        for (int i = 0; i < adulterada.size(); i++) if (adulterada.get(i).operacion == CuadruploIr.Operacion.CALL_INDIRECT) {
            ValorIr destino = adulterada.get(i).fuente1.llamada.puntero;
            adulterada.add(i - 1, new CuadruploIr(CuadruploIr.Operacion.STORE, OperandoAtomico.valor(destino), OperandoAtomico.valor(ValorIr.entero(9)), null, O)); break;
        }
        rechaza(codigo(original, principal, adulterada), "CUADRUPLO_INVALIDO_STORE");
        ModuloAtomicoIr m = aliasPuntero(original); exigir(new VerificadorAtomicoIr().verificar(m).isEmpty(), "Una copia de puntero válido perdió procedencia");
        exigir(correr(m).equals("1\n2\n"), "La copia del destino cambió la llamada");
        FuncionAtomicaIr main = principal(m); List<CuadruploIr> qs = new ArrayList<>(main.codigo);
        int posicion = -1; ValorIr puntero = null;
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.COPY && qs.get(i).destino.valor.id.equals("audAlias")) {
            posicion = i + 1; puntero = qs.get(i).destino.valor; break;
        }
        qs.add(posicion, new CuadruploIr(CuadruploIr.Operacion.STORE, OperandoAtomico.valor(puntero), OperandoAtomico.valor(ValorIr.entero(9)), null, O));
        rechaza(codigo(m, main, qs), "CUADRUPLO_INVALIDO_STORE");
        // Leer una dirección de función como si fuera una celda int también se rechaza.
        qs = new ArrayList<>(main.codigo); ValorIr r = main.codigo.stream().filter(q -> q.operacion == CuadruploIr.Operacion.GET_RETURN
                && q.destino.valor.tipo.equals(TipoIr.INT)).findFirst().orElseThrow().destino.valor;
        ValorIr carga = ValorIr.temporal("audCarga", TipoIr.INT);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.GET_RETURN && qs.get(i).destino.valor.id.equals(r.id)) {
            qs.add(i + 1, new CuadruploIr(CuadruploIr.Operacion.LOAD, OperandoAtomico.valor(puntero), OperandoAtomico.valor(ValorIr.entero(0)), OperandoAtomico.valor(carga), O)); break;
        }
        rechaza(agregarValor(codigo(m, main, qs), main, carga), "CUADRUPLO_INVALIDO_LOAD");
    }
    private static void presentacion() throws IOException {
        ModuloIr m = generar(programa("", "int L0=3; c.imprimirLinea(L0); c.imprimirLinea(\"L0\"); float f=100000000000000000000000.0; c.imprimirLinea(f);"));
        PresentacionDidacticaIr.Resultado p = PresentacionDidacticaIr.desdeTipada(m);
        exigir(p.cuadruplosTsv.contains("L0#") && p.triplosTsv.contains("\"L0\""), "Variable/etiqueta o string perdieron identidad visible");
        exigir(PresentacionDidacticaIr.atomicaSimplificada(m, new LinealizadorIr().transformar(m)).contains("L0#"), "La vista física confundió variable y etiqueta");
        exigir(new ImpresorIr().imprimir(m).contains("9.999999999999999E22:float") && p.codigo.contains("9.999999999999999E22"), "El float cambió según el JDK o la vista");
        exigir(correr(m, false).equals("3\nL0\n9.999999999999999E22\n") && correr(m, true).equals("3\nL0\n9.999999999999999E22\n"), "Presentación/executor discrepan");
    }
    private static void datosNativos() throws IOException {
        ModuloAtomicoIr m = new LinealizadorIr().transformar(generar(programa("int n=1; float f=2.0; bool b=true;", "")));
        LayoutProgramaIr.Clase c = m.layout.clases.get("Main");
        List<String> ids = new ArrayList<>(c.tiposCampos.keySet()); Collections.reverse(ids);
        Map<String, TipoIr> tipos = new LinkedHashMap<>(); for (String id : ids) tipos.put(id, c.tiposCampos.get(id));
        ModuloAtomicoIr reordenada = clase(m, new LayoutProgramaIr.Clase(c.nombre, c.base, false, c.tamano, c.campos, tipos, c.virtuales, c.inicializador));
        exigir(new VerificadorAtomicoIr().verificar(reordenada).isEmpty(), "El orden de un diccionario no cambia sus offsets");
        String vector = DatosNativosIr.clase("Main") + "_campos:\n    dq rt_tipo_i\n    dq rt_tipo_f\n    dq rt_tipo_b\n";
        exigir(new SoporteNativoIr().nasm(m).contains(vector) && new SoporteNativoIr().nasm(reordenada).contains(vector),
                "Los descriptores nativos no siguen los offsets de sus campos");
        rechaza(clase(m, new LayoutProgramaIr.Clase(c.nombre, c.base, false, c.tamano + 8, c.campos, c.tiposCampos, c.virtuales, c.inicializador)),
                "TAMANO_CLASE_INCONSISTENTE");
    }
    private static void ordenYAsignaciones() throws IOException {
        salida(programa("list<int> xs=[1,2]; int indice() { xs=[9]; return 1; } int valor() { return 7; }",
                "list<int> anterior=xs; xs[indice()]=valor(); c.imprimirLinea(anterior); c.imprimirLinea(xs);"), "[1, 7]\n[9]\n");
        salida(programa("list<int> xs=[1]; string cambiar() { xs[0]=9; return \"X\"; }",
                "c.imprimirLinea(xs+cambiar()); c.imprimirLinea(xs);"), "[1]X\n[9]\n");
        salida("import runtime.Consola; { public class Caja { public int n=1; } public class Main { Caja obj=new Caja(); "
                + "int cambiar() { obj=new Caja(); return 8; } public main() { Consola c=new Consola(); Caja viejo=obj; "
                + "obj.n+=cambiar(); c.imprimirLinea(viejo.n); c.imprimirLinea(obj.n); } } }", "9\n1\n");
        salida(programa("int n=1; int tocar() { n=9; return 2; }", "c.imprimirLinea(n+tocar()); n=1; n+=tocar(); c.imprimirLinea(n);"), "3\n3\n");
        salida(programa("", "bool a=true; bool b=false; bool r=(a && (b || true)) || c.leerBool(); c.imprimirLinea(r);"), "true\n");
    }
    private static final class Expresion {
        final String texto; final long valor;
        Expresion(String texto, long valor) { this.texto = texto; this.valor = valor; }
    }
    private static Expresion expresion(Random random, int profundidad) {
        if (profundidad == 0) { long n = random.nextInt(2001) - 1000; return new Expresion(Long.toString(n), n); }
        Expresion a = expresion(random, profundidad - 1); int operacion = random.nextInt(4);
        long divisor = random.nextInt(19) + 1;
        Expresion b = operacion == 3 ? new Expresion(Long.toString(divisor), divisor) : expresion(random, profundidad - 1);
        String op = new String[] {"+", "-", "*", "/"}[operacion];
        long v = operacion == 0 ? a.valor + b.valor : operacion == 1 ? a.valor - b.valor : operacion == 2 ? a.valor * b.valor : a.valor / b.valor;
        return new Expresion("(" + a.texto + op + "(" + b.texto + "))", v);
    }
    private static void aritmetica() throws IOException {
        Random random = new Random(20261010L); StringBuilder cuerpo = new StringBuilder(), esperado = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            Expresion e = expresion(random, 4); cuerpo.append("c.imprimirLinea(").append(e.texto).append(");"); esperado.append(e.valor).append('\n');
        }
        cuerpo.append("c.imprimirLinea(9223372036854775807+1); c.imprimirLinea(-9223372036854775808 / -1);");
        esperado.append("-9223372036854775808\n-9223372036854775808\n");
        salida(programa("", cuerpo.toString()), esperado.toString());
        salida(programa("", "float grande=1" + "0".repeat(308) + ".0; "
                + "float infinito=grande*grande; float nan=infinito-infinito; c.imprimirLinea(nan==nan); c.imprimirLinea(nan!=nan); "
                + "c.imprimirLinea(nan<1.0); c.imprimirLinea(0.0 == -0.0); c.imprimirLinea(-0.0);"), "false\ntrue\nfalse\ntrue\n-0.0\n");
    }
}
