import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Atomicidad, layout, protocolo de llamadas y comparación ejecutable H2. */
public final class PruebasIrAtomica {
    private static int casos;
    private static final OrigenIr O = new OrigenIr("atomica.txt", 2, 3);
    public static void main(String[] args) { ejecutar(); }
    static void ejecutar() {
        casos = 0;
        modelo(); layout(); convencion(); prologo(); rechazos(); ejecucion(); division();
        System.out.println("OK: " + casos + " comprobaciones de IR atómica, offsets y llamadas.");
    }
    private static void comprobar(boolean b, String detalle) { casos++; if (!b) throw new AssertionError(detalle); }
    private static boolean llamada(CuadruploIr q) { return q.operacion == CuadruploIr.Operacion.CALL || q.operacion == CuadruploIr.Operacion.CALL_INDIRECT || q.operacion == CuadruploIr.Operacion.LLAMAR_RUNTIME; }
    private static void modelo() {
        ModuloIr fuente = PruebasIr.ejemploCompleto(); ModuloAtomicoIr a = new LinealizadorIr().transformar(fuente);
        comprobar(new VerificadorAtomicoIr().verificar(a).isEmpty(), "Ejemplo completo se linealiza");
        comprobar(new ImpresorAtomicoIr().imprimir(a).equals(new ImpresorAtomicoIr().imprimir(new LinealizadorIr().transformar(PruebasIr.ejemploCompleto()))), "Cuádruplos/layout deterministas");
        comprobar(a.funciones.stream().flatMap(f -> f.codigo.stream()).noneMatch(q -> q.operacion.name().equals("CONDICIONAL") || q.operacion.name().equals("CICLO")), "Solo control primitivo");
        for (FuncionAtomicaIr f : a.funciones) {
            LayoutProgramaIr.Marco m = a.layout.marcos.get(f.firma.id());
            comprobar(m.tamano % 16 == 0 && m.tamano >= m.bytesValores + m.bytesSalientes, "Marco alineado sin solapamientos");
            for (CuadruploIr q : f.codigo) {
                for (OperandoAtomico o : Arrays.asList(q.fuente1, q.fuente2, q.destino)) if (o != null && o.clase == OperandoAtomico.Clase.VALOR && o.valor.clase != ValorIr.Clase.CONSTANTE)
                    comprobar(m.offset(o.valor) < 0 && m.offset(o.valor) % 8 == 0, "Todos los valores tienen offset");
                if (llamada(q)) comprobar(q.destino == null && q.fuente2.valor.literal.equals((long) q.fuente1.llamada.parametros.size()), "CALL solo destino/count, retorno separado");
            }
        }
        FuncionAtomicaIr main = a.funciones.get(0);
        boolean virtual = false, campos = false, lista = false;
        for (int i = 0; i < main.codigo.size(); i++) {
            CuadruploIr q = main.codigo.get(i);
            if (q.operacion == CuadruploIr.Operacion.CALL_INDIRECT) {
                // Las cargas ocurren antes de preparar argumentos.
                ValorIr ptr = q.fuente1.llamada.puntero;
                CuadruploIr carga = main.codigo.stream().filter(c -> c.destino != null && c.destino.clase == OperandoAtomico.Clase.VALOR && c.destino.valor.id.equals(ptr.id)).findFirst().orElseThrow();
                comprobar(carga.operacion == CuadruploIr.Operacion.LOAD && carga.fuente1.valor.tipo.equals(TipoIr.direccion(TipoIr.INT)), "Destino virtual mediante carga separada"); virtual = true;
                comprobar(q.fuente1.llamada.registroPuntero.equals("r11"), "Destino indirecto reservado en R11");
            }
            if (q.operacion == CuadruploIr.Operacion.LEA && q.destino.valor.id.equals("dc")) { comprobar(q.fuente2.valor.literal.equals(16L), "Campo Base.x resuelto +16"); campos = true; }
            if (q.operacion == CuadruploIr.Operacion.LEA && q.destino.valor.id.equals("de")) {
                comprobar(q.fuente2.valor.clase == ValorIr.Clase.TEMPORAL, "Índice usa offset calculado en temporal"); lista = true;
            }
        }
        comprobar(virtual && campos && lista, "Proyección de todas las formas de direccionamiento");
        try { main.codigo.clear(); comprobar(false, "Código mutable"); } catch (UnsupportedOperationException e) { comprobar(true, "Código inmutable"); }
        try { a.layout.marcos.clear(); comprobar(false, "Layout mutable"); } catch (UnsupportedOperationException e) { comprobar(true, "Layout inmutable"); }
    }
    private static FirmaIr firma(String c, String n, TipoIr r, FirmaIr.Visibilidad vis, TipoIr... ps) { return new FirmaIr(c, n, r, vis, Arrays.asList(ps)); }
    private static FuncionIr vacia(FirmaIr firma, ValorIr retorno) {
        List<ValorIr> parametros = new ArrayList<>(); for (int i = 0; i < firma.parametros.size(); i++) parametros.add(ValorIr.parametro("p" + i, firma.parametros.get(i)));
        return new FuncionIr(firma, ValorIr.parametro("this", TipoIr.objeto(firma.propietaria)), parametros, Collections.emptyList(), Collections.singletonList(new BloqueIr("inicio", Collections.emptyList(), TerminadorIr.retorno(retorno, O))));
    }
    private static void layout() {
        SimboloIr base = new SimboloIr("sBase", "edad", TipoIr.INT, SimboloIr.Rol.CAMPO, "Persona", O);
        SimboloIr derivado = new SimboloIr("sDerivado", "edad", TipoIr.FLOAT, SimboloIr.Rol.CAMPO, "Alumno", O);
        FirmaIr f = firma("Persona", "f", TipoIr.INT, FirmaIr.Visibilidad.PUBLICA);
        FirmaIr override = firma("Alumno", "f", TipoIr.INT, FirmaIr.Visibilidad.PUBLICA);
        FirmaIr privada = firma("Persona", "oculta", TipoIr.INT, FirmaIr.Visibilidad.PRIVADA);
        FirmaIr otro = firma("Alumno", "g", TipoIr.INT, FirmaIr.Visibilidad.PUBLICA);
        ModuloIr m = new ModuloIr("Layout", Arrays.asList(new ClaseIr("Alumno", "Persona", Collections.singletonList(derivado)), new ClaseIr("Persona", null, Collections.singletonList(base))),
                Arrays.asList(vacia(override, ValorIr.entero(2)), vacia(f, ValorIr.entero(1)), vacia(privada, ValorIr.entero(3)), vacia(otro, ValorIr.entero(4))), null);
        ModuloAtomicoIr a = new LinealizadorIr().transformar(m);
        LayoutProgramaIr.Clase p = a.layout.clases.get("Persona"), d = a.layout.clases.get("Alumno");
        comprobar(p.tamano == 24 && d.tamano == 32, "Tamaño heredado y campos de 8 bytes");
        comprobar(p.campos.get(base.id) == 16 && d.campos.get(base.id) == 16 && d.campos.get(derivado.id) == 24, "Campos homónimos mantienen identidad y prefijo");
        comprobar(p.offsetVirtual(f) == 0 && d.offsetVirtual(override) == 0 && d.offsetVirtual(otro) == 8, "Slots de override estables");
        comprobar(d.virtuales.size() == 2 && d.virtuales.get(0).id().equals(override.id()), "Override reemplaza dirección; privados no ocupan slots");
        comprobar(!LayoutProgramaIr.nombreFuncion(firma("A", "f", TipoIr.INT, FirmaIr.Visibilidad.PUBLICA, TipoIr.INT))
                .equals(LayoutProgramaIr.nombreFuncion(firma("A", "f", TipoIr.INT, FirmaIr.Visibilidad.PUBLICA, TipoIr.FLOAT))), "Mangling distingue sobrecargas");
    }
    private static void convencion() {
        List<TipoIr> tipos = new ArrayList<>(); tipos.add(TipoIr.objeto("Main"));
        for (int i = 0; i < 7; i++) tipos.add(TipoIr.INT);
        for (int i = 0; i < 9; i++) tipos.add(TipoIr.FLOAT);
        List<LugarArgumentoIr> salientes = ConvencionLlamadasIr.argumentos(tipos, false), entrantes = ConvencionLlamadasIr.argumentos(tipos, true);
        comprobar(salientes.get(0).registro.equals("rdi") && salientes.get(5).registro.equals("r9"), "Receptor y seis GPR System V");
        comprobar(salientes.get(6).registro == null && salientes.get(6).offset == 0 && salientes.get(7).offset == 8, "Overflow GPR en pila");
        comprobar(entrantes.get(6).offset == 16 && entrantes.get(7).offset == 24, "Offsets entrantes RBP después de retorno y RBP guardado");
        comprobar(salientes.get(8).registro.equals("xmm0") && salientes.get(15).registro.equals("xmm7"), "SSE independiente de GPR");
        comprobar(salientes.get(16).registro == null && salientes.get(16).offset == 16 && entrantes.get(16).offset == 32, "Overflow SSE comparte orden de pila");
        comprobar(ConvencionLlamadasIr.retorno(TipoIr.INT).equals("rax") && ConvencionLlamadasIr.retorno(TipoIr.FLOAT).equals("xmm0"), "Retornos escalares System V");
        List<LugarArgumentoIr> consola = ConvencionLlamadasIr.argumentos(Arrays.asList(TipoIr.objeto("Consola"), TipoIr.STRING), false);
        comprobar(consola.get(0).registro.equals("rdi") && consola.get(1).registro.equals("rsi"), "Consola sigue la misma convención");
        LlamadaAtomica indirecta = LlamadaAtomica.usuario(firma("Main", "muchos", TipoIr.INT, FirmaIr.Visibilidad.PUBLICA,
                tipos.subList(1, tipos.size()).toArray(new TipoIr[0])), ValorIr.temporal("destino", TipoIr.direccion(TipoIr.INT)));
        comprobar(indirecta.registroPuntero.equals("r11") && salientes.stream().noneMatch(l -> indirecta.registroPuntero.equals(l.registro)),
                "R11 no pisa ninguno de los seis GPR, ocho SSE ni argumentos en pila");
        comprobar(LlamadaAtomica.consola(RuntimeConsolaId.CONSOLA_IMPRIMIR_LINEA).registroPuntero == null,
                "Llamadas directas no reclaman registro de destino indirecto");
    }
    private static void prologo() {
        for (int ranuras : new int[] {0, 1, 2, 3, 7, 8}) for (int argumentos : new int[] {0, 1, 6, 7, 15, 17}) {
            LayoutProgramaIr.ConstructorMarco c = new LayoutProgramaIr.ConstructorMarco();
            for (int i = 0; i < ranuras; i++) c.valor(ValorIr.temporal("t" + i, TipoIr.INT));
            c.maxArgumentos = argumentos; LayoutProgramaIr.Marco m = c.construir();
            long rspAntesDeCall = 0x100000L;
            long rspEntrada = rspAntesDeCall - 8, rbp = rspEntrada - 8, rspCuerpo = rbp - m.tamano;
            comprobar(rspEntrada % 16 == 8 && rbp % 16 == 0 && rspCuerpo % 16 == 0, "CALL/push rbp/sub preservan alineación");
            comprobar(rspCuerpo + m.bytesSalientes <= rbp - m.bytesValores, "Área saliente completa no invade ranuras");
            comprobar(rspCuerpo + m.tamano + 8 + 8 == rspAntesDeCall, "Epílogo restaura RSP original");
        }
    }
    private static ModuloAtomicoIr sustituir(ModuloAtomicoIr m, int funcion, List<CuadruploIr> codigo) {
        List<FuncionAtomicaIr> fs = new ArrayList<>(m.funciones); FuncionAtomicaIr f = fs.get(funcion);
        fs.set(funcion, new FuncionAtomicaIr(f.firma, f.receptor, f.parametros, codigo));
        return new ModuloAtomicoIr(m.nombre, m.entrada, m.layout, fs);
    }
    private static void rechaza(ModuloAtomicoIr m, String codigo) {
        List<String> e = new VerificadorAtomicoIr().verificar(m);
        comprobar(e.stream().anyMatch(s -> s.contains(codigo)), "Falta " + codigo + ": " + e);
    }
    private static void rechazos() {
        ModuloAtomicoIr m = new LinealizadorIr().transformar(PruebasIr.ejemploCompleto());
        List<CuadruploIr> qs = new ArrayList<>(m.funciones.get(0).codigo);
        int indice = -1; for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.LEA_LOCAL) { indice = i; break; }
        CuadruploIr anterior = qs.get(indice);
        qs.set(indice, new CuadruploIr(anterior.operacion, OperandoAtomico.valor(ValorIr.entero(-999)), null, anterior.destino, O));
        rechaza(sustituir(m, 0, qs), "LEA_LOCAL");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.LEA && qs.get(i).destino.valor.id.equals("dc")) {
            CuadruploIr q = qs.get(i); qs.set(i, new CuadruploIr(q.operacion, q.fuente1, OperandoAtomico.valor(ValorIr.entero(24)), q.destino, O)); break;
        }
        rechaza(sustituir(m, 0, qs), "CUADRUPLO_INVALIDO_LEA");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.PARAM) { qs.remove(i); break; }
        rechaza(sustituir(m, 0, qs), "CUADRUPLO_INVALIDO");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.PARAM) {
            CuadruploIr q = qs.get(i); LugarArgumentoIr l = q.fuente2.argumento;
            qs.set(i, new CuadruploIr(q.operacion, q.fuente1, OperandoAtomico.argumento(new LugarArgumentoIr(l.indice, l.tipo, "r11", 0, false)), null, O)); break;
        }
        rechaza(sustituir(m, 0, qs), "CUADRUPLO_INVALIDO_LLAMAR_RUNTIME");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.GET_RETURN) { qs.remove(i); break; }
        rechaza(sustituir(m, 0, qs), "GET_RETURN_FALTANTE");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.LABEL) { qs.add(i + 1, qs.get(i)); break; }
        rechaza(sustituir(m, 0, qs), "LABEL_DUPLICADA");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        qs.add(qs.size() - 1, new CuadruploIr(CuadruploIr.Operacion.JUMP, OperandoAtomico.etiqueta("Ausente"), null, null, O));
        rechaza(sustituir(m, 0, qs), "SALTO_INEXISTENTE");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        int lea = -1, load = -1;
        for (int i = 0; i < qs.size(); i++) { if (qs.get(i).operacion == CuadruploIr.Operacion.LEA_LOCAL) lea = i; if (qs.get(i).operacion == CuadruploIr.Operacion.LOAD && qs.get(i).destino.valor.id.equals("carga")) { load = i; break; } }
        Collections.swap(qs, lea, load); rechaza(sustituir(m, 0, qs), "USO_SIN_DEFINICION");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.CALL_INDIRECT) {
            CuadruploIr q = qs.get(i); LlamadaAtomica falso = LlamadaAtomica.usuario(q.fuente1.llamada.firma, ValorIr.temporal("dl", TipoIr.direccion(TipoIr.INT)));
            qs.set(i, new CuadruploIr(q.operacion, OperandoAtomico.llamada(falso), q.fuente2, null, O)); break;
        }
        rechaza(sustituir(m, 0, qs), "CALL_INDIRECT");
        qs = new ArrayList<>(m.funciones.get(0).codigo);
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).operacion == CuadruploIr.Operacion.EQ) {
            CuadruploIr q = qs.get(i); qs.set(i, new CuadruploIr(q.operacion, OperandoAtomico.valor(ValorIr.cadena("a")), OperandoAtomico.valor(ValorIr.cadena("a")), q.destino, O)); break;
        }
        rechaza(sustituir(m, 0, qs), "CUADRUPLO_INVALIDO_EQ");
        dominancia();
        LayoutProgramaIr.Marco marco = m.layout.marcos.get(m.funciones.get(0).firma.id());
        Map<String, Integer> offsets = new LinkedHashMap<>(marco.valores); offsets.put("this", marco.locales.values().iterator().next());
        Map<String, LayoutProgramaIr.Marco> marcos = new LinkedHashMap<>(m.layout.marcos);
        marcos.put(m.funciones.get(0).firma.id(), new LayoutProgramaIr.Marco(offsets, marco.locales, marco.tiposValores, marco.tiposLocales, marco.bytesValores, marco.maxArgumentos));
        rechaza(new ModuloAtomicoIr(m.nombre, m.entrada, new LayoutProgramaIr(m.layout.clases, marcos), m.funciones), "OFFSET_PILA");
    }
    private static void dominancia() {
        FirmaIr f = firma("Main", "f", TipoIr.INT, FirmaIr.Visibilidad.PRIVADA);
        ValorIr yo = ValorIr.parametro("this", TipoIr.objeto("Main")), v = ValorIr.temporal("v", TipoIr.INT);
        OperandoAtomico vo = OperandoAtomico.valor(v);
        List<CuadruploIr> qs = Arrays.asList(
            new CuadruploIr(CuadruploIr.Operacion.READ_PARAM, OperandoAtomico.argumento(ConvencionLlamadasIr.argumentos(Collections.singletonList(yo.tipo), true).get(0)), null, OperandoAtomico.valor(yo), O),
            new CuadruploIr(CuadruploIr.Operacion.LABEL, null, null, OperandoAtomico.etiqueta("inicio"), O),
            new CuadruploIr(CuadruploIr.Operacion.JUMP_IF_FALSE, OperandoAtomico.valor(ValorIr.booleano(true)), OperandoAtomico.etiqueta("no"), null, O),
            new CuadruploIr(CuadruploIr.Operacion.COPY, OperandoAtomico.valor(ValorIr.entero(1)), null, vo, O),
            new CuadruploIr(CuadruploIr.Operacion.JUMP, OperandoAtomico.etiqueta("fin"), null, null, O),
            new CuadruploIr(CuadruploIr.Operacion.LABEL, null, null, OperandoAtomico.etiqueta("no"), O),
            new CuadruploIr(CuadruploIr.Operacion.JUMP, OperandoAtomico.etiqueta("fin"), null, null, O),
            new CuadruploIr(CuadruploIr.Operacion.LABEL, null, null, OperandoAtomico.etiqueta("fin"), O),
            new CuadruploIr(CuadruploIr.Operacion.SET_RETURN, vo, null, null, O),
            new CuadruploIr(CuadruploIr.Operacion.RETURN, null, null, null, O));
        LayoutProgramaIr.ConstructorMarco marco = new LayoutProgramaIr.ConstructorMarco(); marco.valor(yo); marco.valor(v);
        LayoutProgramaIr.Clase clase = new LayoutProgramaIr.Clase("Main", null, false, 16, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
        ModuloAtomicoIr m = new ModuloAtomicoIr("Dominancia", null, new LayoutProgramaIr(Collections.singletonMap("Main", clase), Collections.singletonMap(f.id(), marco.construir())), Collections.singletonList(new FuncionAtomicaIr(f, yo, Collections.emptyList(), qs)));
        rechaza(m, "USO_SIN_DEFINICION");
    }
    private static void ejecucion() {
        Path p = null;
        try {
            p = Files.createTempFile("ir-atomica-", ".txt");
            String fuente = "import runtime.Consola; { public class Main { int calc(int a, int b, int c) { return a + b * c; } public int doble(int x) { return x * 2; } public main() { Consola c = new Consola(); int n = calc(1, 2, 3); c.imprimirLinea(n); c.imprimirLinea(doble(n)); while (n > 4) { n -= 1; } if (false && c.leerBool()) { c.imprimir(\"X\"); } else { c.imprimirLinea(n); } } } }";
            ResultadoGeneracionIr r = new GeneradorIr().generar(Main.analizarProyecto(p, fuente, false), null); comprobar(r.esExitoso(), "Fuente válida H2");
            ModuloAtomicoIr a = new LinealizadorIr().transformar(r.modulo);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); new InterpreteAtomicoIr().ejecutar(a, new ConsolaReferencia(new ByteArrayInputStream(new byte[0]), out), 10000);
            comprobar(out.toString(StandardCharsets.UTF_8).equals("7\n14\n4\n"), "Llamadas, virtual, control y cortocircuito preservados");
            FuncionAtomicaIr calc = a.funciones.stream().filter(f -> f.firma.nombre.equals("calc")).findFirst().orElseThrow();
            int mul = -1, add = -1;
            for (int i = 0; i < calc.codigo.size(); i++) { if (calc.codigo.get(i).operacion == CuadruploIr.Operacion.MUL) mul = i; if (calc.codigo.get(i).operacion == CuadruploIr.Operacion.ADD) add = i; }
            comprobar(mul >= 0 && add > mul && calc.codigo.get(add).fuente2.valor.id.equals(calc.codigo.get(mul).destino.valor.id), "a+b*c usa MUL temporal y ADD temporal");
            comprobar(a.funciones.stream().flatMap(f -> f.codigo.stream()).anyMatch(q -> q.operacion == CuadruploIr.Operacion.JUMP_IF_FALSE), "Control linealizado");
        } catch (IOException e) { throw new AssertionError(e); }
        finally { if (p != null) try { Files.delete(p); } catch (IOException e) { throw new AssertionError(e); } }
    }
    private static void division() {
        Path p = null;
        try {
            p = Files.createTempFile("ir-division-", ".txt");
            String prefijo = "import runtime.Consola; { public class Main { public main() { Consola c = new Consola(); ";
            String[] expresiones = {"-9223372036854775808 / -1", "-9223372036854775808 / 1", "-7 / 2", "7 / -2", "0 / -1", "1 / 0", "-9223372036854775808 / 0"};
            String[] esperadas = {"-9223372036854775808\n", "-9223372036854775808\n", "-3\n", "-3\n", "0\n"};
            for (int i = 0; i < expresiones.length; i++) {
                ResultadoGeneracionIr r = new GeneradorIr().generar(Main.analizarProyecto(p,
                        prefijo + "c.imprimirLinea(" + expresiones[i] + "); } } }", false), null);
                comprobar(r.esExitoso(), "División extrema genera IR: " + expresiones[i]);
                ModuloAtomicoIr m = new LinealizadorIr().transformar(r.modulo);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try {
                    new InterpreteAtomicoIr().ejecutar(m, new ConsolaReferencia(new ByteArrayInputStream(new byte[0]), out), 1000);
                    comprobar(i < esperadas.length && out.toString(StandardCharsets.UTF_8).equals(esperadas[i]), "Wrap/truncamiento de división: " + expresiones[i]);
                } catch (ErrorEjecucionIr e) {
                    comprobar(i >= esperadas.length && e.codigo.equals("DIVISION_POR_CERO") && out.size() == 0
                            && e.origen.archivo.equals(p.toString()), "División por cero conserva diagnóstico y origen");
                }
            }
        } catch (IOException e) { throw new AssertionError(e); }
        finally { if (p != null) try { Files.delete(p); } catch (IOException e) { throw new AssertionError(e); } }
    }
}
