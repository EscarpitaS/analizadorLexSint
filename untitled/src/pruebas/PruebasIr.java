import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/** IR construida manualmente: contratos, CFG, dominancia y rechazo previo a exportar. */
public final class PruebasIr {
    private static final OrigenIr O = new OrigenIr("ejemplo.txt", 4, 7);
    private static int casos;
    public static void main(String[] args) throws Exception {
        ejecutar();
        if (args.length == 1) Files.writeString(Path.of(args[0]), new ImpresorIr().imprimir(ejemploCompleto()));
    }
    static void ejecutar() {
        casos = 0;
        modeloYExportacion();
        flujo();
        declaracionesYFirmas();
        operacionesInvalidas();
        puenteSemantico();
        System.out.println("OK: " + casos + " casos de IR tipada y verificación.");
    }
    private static void comprobar(boolean condicion, String nombre) {
        casos++;
        if (!condicion) throw new AssertionError(nombre);
    }
    private static void acepta(ModuloIr m) {
        ResultadoVerificacionIr r = new VerificadorIr().verificar(m);
        comprobar(r.esExitoso(), "IR válida rechazada: " + r.getErrores());
    }
    private static void rechaza(ModuloIr m, String codigo) {
        ResultadoVerificacionIr r = new VerificadorIr().verificar(m);
        comprobar(r.getErrores().stream().anyMatch(e -> e.codigo.equals(codigo)), "Falta " + codigo + ": " + r.getErrores());
    }
    private static void lanza(Runnable accion, Class<? extends Exception> tipo) {
        try { accion.run(); } catch (Exception e) {
            comprobar(tipo.isInstance(e), "Excepción incorrecta: " + e); return;
        }
        comprobar(false, "Falta excepción " + tipo.getSimpleName());
    }
    private static ValorIr t(String id, TipoIr tipo) { return ValorIr.temporal(id, tipo); }
    private static ValorIr yo(String clase) { return ValorIr.parametro("this", TipoIr.objeto(clase)); }
    private static FirmaIr firma(String clase, String nombre, TipoIr retorno, TipoIr... parametros) {
        return new FirmaIr(clase, nombre, retorno, FirmaIr.Visibilidad.PUBLICA, Arrays.asList(parametros));
    }
    private static FirmaIr privada(String clase, String nombre, TipoIr retorno, TipoIr... parametros) {
        return new FirmaIr(clase, nombre, retorno, FirmaIr.Visibilidad.PRIVADA, Arrays.asList(parametros));
    }
    private static InstruccionIr op(InstruccionIr.Operacion op, ValorIr resultado, ValorIr... args) {
        return InstruccionIr.operacion(op, resultado, O, args);
    }
    private static BloqueIr b(String id, TerminadorIr fin, InstruccionIr... instrucciones) {
        return new BloqueIr(id, Arrays.asList(instrucciones), fin);
    }
    private static TerminadorIr ret(ValorIr v) { return TerminadorIr.retorno(v, O); }
    private static FuncionIr funcion(FirmaIr firma, List<SimboloIr> locales, BloqueIr... bloques) {
        List<ValorIr> parametros = new ArrayList<>();
        for (int i = 0; i < firma.parametros.size(); i++) parametros.add(ValorIr.parametro("p" + i, firma.parametros.get(i)));
        return new FuncionIr(firma, yo(firma.propietaria), parametros, locales, Arrays.asList(bloques));
    }
    private static ModuloIr modulo(FuncionIr... funciones) {
        return new ModuloIr("Prueba", singletonList(new ClaseIr("Main", null, emptyList())), Arrays.asList(funciones), null);
    }
    private static ModuloIr cuerpo(TipoIr retorno, BloqueIr... bloques) {
        return modulo(funcion(firma("Main", "f", retorno), emptyList(), bloques));
    }
    private static SimboloIr local(String id, TipoIr tipo) { return new SimboloIr(id, "x", tipo, SimboloIr.Rol.LOCAL, "Main", O); }

    static ModuloIr ejemploCompleto() {
        List<InstruccionIr> ins = new ArrayList<>();
        FirmaIr main = firma("Main", "main", TipoIr.VOID);
        FirmaIr secreto = privada("Main", "secreto", TipoIr.INT, TipoIr.INT);
        FirmaIr publico = firma("Base", "leer", TipoIr.INT, TipoIr.INT);
        FirmaIr accion = privada("Main", "accion", TipoIr.VOID);
        SimboloIr local = local("s0", TipoIr.INT);
        SimboloIr campo = new SimboloIr("s1", "x", TipoIr.INT, SimboloIr.Rol.CAMPO, "Base", O);
        ValorIr uno = ValorIr.entero(1), dos = ValorIr.entero(2), floatUno = ValorIr.flotante(1.0);
        ValorIr dirLocal = t("dl", TipoIr.direccion(TipoIr.INT));
        ins.add(InstruccionIr.direccionLocal(dirLocal, local, O));
        ins.add(op(InstruccionIr.Operacion.GUARDAR, null, dirLocal, uno));
        ins.add(op(InstruccionIr.Operacion.CARGAR, t("carga", TipoIr.INT), dirLocal));
        ins.add(InstruccionIr.direccionCampo(t("dc", TipoIr.direccion(TipoIr.INT)), campo, yo("Main"), O));
        ins.add(op(InstruccionIr.Operacion.COPIAR, t("copia", TipoIr.INT), uno));
        for (InstruccionIr.Operacion operacion : Arrays.asList(InstruccionIr.Operacion.SUMAR, InstruccionIr.Operacion.RESTAR,
                InstruccionIr.Operacion.MULTIPLICAR, InstruccionIr.Operacion.DIVIDIR)) {
            ins.add(op(operacion, t("i" + operacion.name(), TipoIr.INT), uno, dos));
            ins.add(op(operacion, t("f" + operacion.name(), TipoIr.FLOAT), floatUno, ValorIr.flotante(2.5)));
        }
        ins.add(op(InstruccionIr.Operacion.NEGAR, t("neg", TipoIr.INT), uno));
        ins.add(op(InstruccionIr.Operacion.NO, t("no", TipoIr.BOOL), ValorIr.booleano(false)));
        for (InstruccionIr.Operacion operacion : Arrays.asList(InstruccionIr.Operacion.IGUAL, InstruccionIr.Operacion.DISTINTO,
                InstruccionIr.Operacion.MENOR, InstruccionIr.Operacion.MENOR_IGUAL, InstruccionIr.Operacion.MAYOR, InstruccionIr.Operacion.MAYOR_IGUAL))
            ins.add(op(operacion, t("cmp" + operacion.name(), TipoIr.BOOL), uno, dos));
        ins.add(InstruccionIr.convertir(t("promocion", TipoIr.FLOAT), InstruccionIr.Conversion.INT_A_FLOAT, uno, O));
        ins.add(InstruccionIr.convertir(t("texto", TipoIr.STRING), InstruccionIr.Conversion.A_STRING, ValorIr.booleano(true), O));
        ins.add(InstruccionIr.convertir(t("nulo", TipoIr.objeto("Base")), InstruccionIr.Conversion.NULO_A_REFERENCIA, ValorIr.nulo(), O));
        ins.add(InstruccionIr.convertir(t("base", TipoIr.objeto("Base")), InstruccionIr.Conversion.SUBIR_CLASE, yo("Main"), O));
        ins.add(InstruccionIr.llamar(false, t("directo", TipoIr.INT), secreto, yo("Main"), O, uno));
        ins.add(InstruccionIr.llamar(true, t("virtual", TipoIr.INT), publico, yo("Main"), O, dos));
        ins.add(InstruccionIr.llamar(false, null, accion, yo("Main"), O));
        ins.add(op(InstruccionIr.Operacion.CONCATENAR, t("concat", TipoIr.STRING), ValorIr.cadena("a\"\\\n\t\u0000"), ValorIr.cadena("á😀")));
        ValorIr lista = t("lista", TipoIr.lista(TipoIr.INT));
        ins.add(op(InstruccionIr.Operacion.CREAR_LISTA, lista, uno, dos));
        ins.add(op(InstruccionIr.Operacion.CREAR_LISTA, t("vacia", TipoIr.lista(TipoIr.lista(TipoIr.FLOAT)))));
        ins.add(op(InstruccionIr.Operacion.LONGITUD_LISTA, t("longitud", TipoIr.INT), lista));
        ins.add(op(InstruccionIr.Operacion.DIRECCION_ELEMENTO, t("de", TipoIr.direccion(TipoIr.INT)), lista, uno));
        ins.add(op(InstruccionIr.Operacion.COMPROBAR_NULO, null, lista));
        ins.add(op(InstruccionIr.Operacion.COMPROBAR_INDICE, null, lista, uno));
        ins.add(op(InstruccionIr.Operacion.CREAR_OBJETO, t("objeto", TipoIr.objeto("Main"))));
        ValorIr consola = t("consola", TipoIr.objeto("Consola"));
        ins.add(op(InstruccionIr.Operacion.CREAR_OBJETO, consola));
        ins.add(InstruccionIr.runtime(RuntimeConsolaId.CONSOLA_IMPRIMIR_LINEA, null, O, consola, ValorIr.cadena("ok")));
        return new ModuloIr("Ejemplo", Arrays.asList(new ClaseIr("Consola", null, emptyList(), true), new ClaseIr("Base", null, singletonList(campo)), new ClaseIr("Main", "Base", emptyList())),
                Arrays.asList(funcion(main, singletonList(local), new BloqueIr("inicio", ins, ret(null))),
                        funcion(secreto, emptyList(), b("inicio", ret(ValorIr.parametro("p0", TipoIr.INT)))),
                        funcion(publico, emptyList(), b("inicio", ret(ValorIr.parametro("p0", TipoIr.INT)))),
                        funcion(accion, emptyList(), b("inicio", ret(null)))), main);
    }
    private static void modeloYExportacion() {
        ModuloIr m = ejemploCompleto(); acepta(m);
        EnumSet<InstruccionIr.Operacion> usadas = EnumSet.noneOf(InstruccionIr.Operacion.class);
        for (FuncionIr f : m.funciones) for (BloqueIr b : f.bloques) for (InstruccionIr i : b.instrucciones) usadas.add(i.operacion);
        comprobar(usadas.equals(EnumSet.allOf(InstruccionIr.Operacion.class)), "Ejemplo debe cubrir todo el catálogo de operaciones");
        String texto = new ImpresorIr().imprimir(m);
        comprobar(texto.equals(new ImpresorIr().imprimir(ejemploCompleto())), "Salida determinista entre módulos equivalentes");
        comprobar(texto.contains("a\\\"\\\\\\n\\t\\u0000") && texto.contains("\\ud83d\\ude00"), "Escapes de constantes");
        comprobar(texto.contains("@ \"ejemplo.txt\":4:7") && texto.contains("&s0:int Main::x"), "Origen e identidad de almacenamiento");
        List<InstruccionIr> originales = new ArrayList<>();
        BloqueIr copia = new BloqueIr("b", originales, ret(null)); originales.add(op(InstruccionIr.Operacion.COPIAR, t("x", TipoIr.INT), ValorIr.entero(1)));
        comprobar(copia.instrucciones.isEmpty(), "Copia defensiva");
        lanza(() -> m.funciones.clear(), UnsupportedOperationException.class);
        lanza(() -> m.funciones.get(0).bloques.get(0).instrucciones.clear(), UnsupportedOperationException.class);
        lanza(() -> TipoIr.lista(TipoIr.VOID), IllegalArgumentException.class);
        lanza(() -> ValorIr.temporal("t", TipoIr.VOID), IllegalArgumentException.class);
        lanza(() -> new OrigenIr("x", 0, 1), IllegalArgumentException.class);
        lanza(() -> new ImpresorIr().imprimir(cuerpo(TipoIr.VOID, b("inicio", null))), IllegalArgumentException.class);
        VerificadorIr verificador = new VerificadorIr();
        ResultadoVerificacionIr invalido = verificador.verificar(cuerpo(TipoIr.VOID, b("inicio", null)));
        comprobar(verificador.verificar(m).esExitoso() && !invalido.esExitoso(), "Verificador reutilizable sin estado residual");
        lanza(() -> invalido.getErrores().clear(), UnsupportedOperationException.class);
    }
    private static void flujo() {
        ValorIr x = t("x", TipoIr.INT), y = t("y", TipoIr.INT);
        InstruccionIr def = op(InstruccionIr.Operacion.COPIAR, x, ValorIr.entero(1));
        TerminadorIr rama = TerminadorIr.condicional(ValorIr.booleano(true), "si", "no", O);
        acepta(cuerpo(TipoIr.INT, b("inicio", rama, def), b("si", ret(x)), b("no", ret(x))));
        acepta(cuerpo(TipoIr.INT, b("inicio", TerminadorIr.salto("cond", O), def),
                b("cond", TerminadorIr.condicional(ValorIr.booleano(true), "ciclo", "fin", O)),
                b("ciclo", TerminadorIr.salto("cond", O), op(InstruccionIr.Operacion.SUMAR, y, x, ValorIr.entero(1))), b("fin", ret(x))));
        rechaza(cuerpo(TipoIr.INT, b("inicio", rama), b("si", TerminadorIr.salto("fin", O), def),
                b("no", TerminadorIr.salto("fin", O)), b("fin", ret(x))), "USO_SIN_DEFINICION");
        rechaza(cuerpo(TipoIr.INT, b("inicio", TerminadorIr.salto("cond", O)),
                b("cond", TerminadorIr.condicional(ValorIr.booleano(false), "ciclo", "fin", O)),
                b("ciclo", TerminadorIr.salto("cond", O), def), b("fin", ret(x))), "USO_SIN_DEFINICION");
        rechaza(cuerpo(TipoIr.INT, b("inicio", ret(y), op(InstruccionIr.Operacion.COPIAR, y, x), def)), "USO_SIN_DEFINICION");
        rechaza(cuerpo(TipoIr.INT, b("inicio", ret(x), op(InstruccionIr.Operacion.SUMAR, x, x, ValorIr.entero(1)))), "USO_SIN_DEFINICION");
        rechaza(cuerpo(TipoIr.INT, b("inicio", ret(x))), "VALOR_INDEFINIDO");
        rechaza(cuerpo(TipoIr.INT, b("inicio", ret(x), def, def)), "VALOR_DUPLICADO");
        rechaza(cuerpo(TipoIr.FLOAT, b("inicio", ret(t("x", TipoIr.FLOAT)), def)), "VALOR_INCONSISTENTE");
        rechaza(cuerpo(TipoIr.INT, b("inicio", ret(ValorIr.parametro("x", TipoIr.INT)), def)), "VALOR_INCONSISTENTE");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", null)), "SIN_TERMINADOR");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", TerminadorIr.salto("ausente", O))), "SALTO_INEXISTENTE");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null)), b("inicio", ret(null))), "BLOQUE_DUPLICADO");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null)), b("muerto", ret(null))), "BLOQUE_INALCANZABLE");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", TerminadorIr.condicional(ValorIr.entero(1), "fin", "fin", O)), b("fin", ret(null))), "CONDICION_INVALIDA");
        rechaza(cuerpo(TipoIr.INT, b("inicio", ret(null))), "RETORNO_INVALIDO");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(ValorIr.entero(1)))), "RETORNO_INVALIDO");
        rechaza(cuerpo(TipoIr.FLOAT, b("inicio", ret(ValorIr.entero(1)))), "RETORNO_INVALIDO");
        rechaza(cuerpo(TipoIr.VOID), "FUNCION_SIN_BLOQUES");
        ResultadoVerificacionIr r = new VerificadorIr().verificar(cuerpo(TipoIr.INT, b("inicio", ret(x))));
        comprobar(r.getErrores().get(0).origen == O && r.getErrores().get(0).funcion.equals("Main::f()")
                && r.getErrores().get(0).bloque.equals("inicio") && r.getErrores().get(0).instruccion == 0, "Diagnóstico ubicado");
    }
    private static void declaracionesYFirmas() {
        FirmaIr f = firma("Main", "f", TipoIr.INT, TipoIr.INT);
        FirmaIr main = firma("Main", "main", TipoIr.VOID);
        FuncionIr valida = funcion(f, emptyList(), b("inicio", ret(ValorIr.parametro("p0", TipoIr.INT))));
        acepta(modulo(valida));
        rechaza(modulo(valida, funcion(firma("Main", "f", TipoIr.FLOAT, TipoIr.INT), emptyList(), b("inicio", ret(ValorIr.flotante(1))))), "FUNCION_DUPLICADA");
        rechaza(modulo(new FuncionIr(f, yo("Main"), emptyList(), emptyList(), valida.bloques)), "PARAMETROS_INVALIDOS");
        rechaza(modulo(new FuncionIr(f, yo("Main"), singletonList(ValorIr.parametro("p0", TipoIr.FLOAT)), emptyList(), valida.bloques)), "PARAMETROS_INVALIDOS");
        rechaza(modulo(new FuncionIr(f, yo("Otra"), valida.parametros, emptyList(), valida.bloques)), "RECEPTOR_INVALIDO");
        rechaza(modulo(new FuncionIr(f, yo("Main"), singletonList(ValorIr.parametro("this", TipoIr.INT)), emptyList(), valida.bloques)), "VALOR_DUPLICADO");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), op(InstruccionIr.Operacion.COPIAR, ValorIr.entero(1), ValorIr.entero(2)))), "RESULTADO_INVALIDO");
        SimboloIr s = local("s", TipoIr.INT);
        rechaza(modulo(funcion(main, Arrays.asList(s, s), b("inicio", ret(null)))), "SIMBOLO_DUPLICADO");
        SimboloIr campo = new SimboloIr("s", "x", TipoIr.INT, SimboloIr.Rol.CAMPO, "Main", O);
        rechaza(modulo(funcion(main, singletonList(campo), b("inicio", ret(null)))), "LOCAL_INVALIDA");
        ModuloIr simple = modulo(funcion(main, emptyList(), b("inicio", ret(null))));
        acepta(new ModuloIr("M", simple.clases, simple.funciones, main));
        rechaza(new ModuloIr("M", simple.clases, simple.funciones, firma("Main", "ausente", TipoIr.VOID)), "ENTRADA_INVALIDA");
        rechaza(new ModuloIr("M", simple.clases, simple.funciones, firma("Main", "main", TipoIr.INT)), "ENTRADA_INVALIDA");
        rechaza(new ModuloIr("M", Arrays.asList(simple.clases.get(0), simple.clases.get(0)), simple.funciones, null), "CLASE_DUPLICADA");
        rechaza(new ModuloIr("M", singletonList(new ClaseIr("Main", "NoExiste", emptyList())), simple.funciones, null), "BASE_INEXISTENTE");
        rechaza(new ModuloIr("M", Arrays.asList(new ClaseIr("Main", "Base", emptyList()), new ClaseIr("Base", "Main", emptyList())), simple.funciones, null), "HERENCIA_CICLICA");
        rechaza(cuerpo(TipoIr.objeto("NoExiste"), b("inicio", ret(t("obj", TipoIr.objeto("NoExiste"))), op(InstruccionIr.Operacion.CREAR_OBJETO, t("obj", TipoIr.objeto("NoExiste"))))), "CLASE_INEXISTENTE");
        rechaza(new ModuloIr("M", singletonList(new ClaseIr("Main", null, singletonList(s))), simple.funciones, null), "CAMPO_INVALIDO");
        comprobar(SeleccionEntradaIr.elegir(Arrays.asList(f, main), null) == main, "Selección de main único");
        FirmaIr otroMain = firma("Otra", "main", TipoIr.VOID);
        comprobar(SeleccionEntradaIr.elegir(Arrays.asList(main, otroMain), "Otra") == otroMain, "Selección explícita");
        lanza(() -> SeleccionEntradaIr.elegir(Arrays.asList(main, otroMain), null), IllegalArgumentException.class);
        lanza(() -> SeleccionEntradaIr.elegir(singletonList(main), "Otra"), IllegalArgumentException.class);
        lanza(() -> SeleccionEntradaIr.elegir(singletonList(privada("Main", "main", TipoIr.VOID)), null), IllegalArgumentException.class);
        List<ClaseIr> jerarquia = Arrays.asList(new ClaseIr("Main", "Base", emptyList()), new ClaseIr("Base", null, emptyList()));
        FuncionIr base = funcion(firma("Base", "f", TipoIr.objeto("Base")), emptyList(), b("inicio", ret(yo("Base"))));
        FuncionIr derivada = funcion(firma("Main", "f", TipoIr.objeto("Main")), emptyList(), b("inicio", ret(yo("Main"))));
        acepta(new ModuloIr("M", jerarquia, Arrays.asList(base, derivada), null));
        rechaza(new ModuloIr("M", jerarquia, Arrays.asList(base, funcion(firma("Main", "f", TipoIr.INT), emptyList(), b("inicio", ret(ValorIr.entero(1))))), null), "SOBRESCRITURA_INVALIDA");
        rechaza(new ModuloIr("M", jerarquia, Arrays.asList(base, funcion(privada("Main", "f", TipoIr.objeto("Main")), emptyList(), b("inicio", ret(yo("Main"))))), null), "SOBRESCRITURA_INVALIDA");
    }
    private static void operacionesInvalidas() {
        for (InstruccionIr.Operacion operacion : InstruccionIr.Operacion.values())
            rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), op(operacion, t("t", TipoIr.BOOL), ValorIr.entero(1), ValorIr.entero(2), ValorIr.entero(3)))), "INSTRUCCION_INVALIDA");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), op(InstruccionIr.Operacion.SUMAR, t("t", TipoIr.FLOAT), ValorIr.entero(1), ValorIr.flotante(2)))), "INSTRUCCION_INVALIDA");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), op(InstruccionIr.Operacion.COPIAR, null, ValorIr.entero(1)))), "RESULTADO_INVALIDO");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), op(InstruccionIr.Operacion.COMPROBAR_NULO, t("t", TipoIr.INT), yo("Main")))), "RESULTADO_INVALIDO");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), op(InstruccionIr.Operacion.CREAR_LISTA, t("t", TipoIr.lista(TipoIr.FLOAT)), ValorIr.entero(1)))), "INSTRUCCION_INVALIDA");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), InstruccionIr.convertir(t("t", TipoIr.INT), InstruccionIr.Conversion.INT_A_FLOAT, ValorIr.flotante(1), O))), "INSTRUCCION_INVALIDA");
        rechaza(cuerpo(TipoIr.VOID, b("inicio", ret(null), InstruccionIr.convertir(t("t", TipoIr.lista(TipoIr.FLOAT)), InstruccionIr.Conversion.SUBIR_CLASE, ValorIr.nulo(), O))), "INSTRUCCION_INVALIDA");
        SimboloIr local = local("s", TipoIr.INT), impostor = local("s", TipoIr.INT);
        rechaza(modulo(funcion(firma("Main", "f", TipoIr.VOID), singletonList(local),
                b("inicio", ret(null), InstruccionIr.direccionLocal(t("d", TipoIr.direccion(TipoIr.INT)), impostor, O)))), "INSTRUCCION_INVALIDA");
        rechaza(modulo(funcion(firma("Main", "f", TipoIr.VOID), singletonList(local),
                b("inicio", ret(null), InstruccionIr.direccionLocal(t("d", TipoIr.direccion(TipoIr.INT)), local, O),
                        op(InstruccionIr.Operacion.GUARDAR, null, t("d", TipoIr.direccion(TipoIr.INT)), ValorIr.flotante(1))))), "INSTRUCCION_INVALIDA");
        FirmaIr callee = privada("Main", "g", TipoIr.INT, TipoIr.INT);
        FuncionIr destino = funcion(callee, emptyList(), b("inicio", ret(ValorIr.entero(0))));
        List<InstruccionIr> llamadas = Arrays.asList(
                InstruccionIr.llamar(true, t("r", TipoIr.INT), callee, yo("Main"), O, ValorIr.entero(1)),
                InstruccionIr.llamar(false, t("r", TipoIr.FLOAT), callee, yo("Main"), O, ValorIr.entero(1)),
                InstruccionIr.llamar(false, t("r", TipoIr.INT), callee, yo("Main"), O),
                InstruccionIr.llamar(false, t("r", TipoIr.INT), callee, yo("Main"), O, ValorIr.flotante(1)),
                InstruccionIr.llamar(false, t("r", TipoIr.INT), privada("Main", "g", TipoIr.FLOAT, TipoIr.INT), yo("Main"), O, ValorIr.entero(1)),
                InstruccionIr.llamar(false, t("r", TipoIr.INT), privada("Main", "ausente", TipoIr.INT), yo("Main"), O));
        for (InstruccionIr llamada : llamadas)
            rechaza(modulo(destino, funcion(firma("Main", "f", TipoIr.VOID), emptyList(), b("inicio", ret(null), llamada))), "INSTRUCCION_INVALIDA");
        acepta(cuerpo(TipoIr.BOOL, b("inicio", ret(t("t", TipoIr.BOOL)), op(InstruccionIr.Operacion.IGUAL, t("t", TipoIr.BOOL), ValorIr.nulo(), yo("Main")))));
    }
    private static void puenteSemantico() {
        comprobar(TipoIr.desdeSemantico(Semantico.listaDe(Semantico.listaDe(Semantico.INT))).equals(TipoIr.lista(TipoIr.lista(TipoIr.INT))), "Tipos de listas anidadas");
        comprobar(TipoIr.desdeSemantico(Semantico.clase("Main")).equals(TipoIr.objeto("Main")), "Tipo de clase");
        lanza(() -> TipoIr.desdeSemantico(Semantico.ERROR), IllegalArgumentException.class);
        CatalogoSimbolosIr catalogo = new CatalogoSimbolosIr();
        Semantico.Simbolo a = Semantico.Simbolo.variable("x", Semantico.INT, Semantico.RolSimbolo.VARIABLE, Semantico.Mutabilidad.MUTABLE, 1, 1);
        Semantico.Simbolo b = Semantico.Simbolo.variable("x", Semantico.INT, Semantico.RolSimbolo.VARIABLE, Semantico.Mutabilidad.MUTABLE, 2, 1);
        SimboloIr sa = catalogo.registrar(a, "Main", O), sb = catalogo.registrar(b, "Main", O);
        comprobar(!sa.id.equals(sb.id) && sa.nombre.equals(sb.nombre), "Locales homónimas conservan identidades distintas");
        comprobar(catalogo.registrar(a, "Main", O) == sa && catalogo.obtener(a) == sa, "Una identidad por símbolo resuelto");
        lanza(() -> catalogo.registrar(a, "Otra", O), IllegalArgumentException.class);
        lanza(() -> new CatalogoSimbolosIr().obtener(a), IllegalArgumentException.class);
        try {
            List<Main.ResultadoAnalisis> valido = Main.analizarProyecto(Path.of("/tmp/ir-valido.txt"), "{ class Main { public main() { } } }", false);
            comprobar(EntradaProyectoIr.desdeResultados(valido).unidades.get(0).getAstValidado() != null, "Entrada de proyecto válido");
            lanza(() -> EntradaProyectoIr.desdeResultados(emptyList()), IllegalArgumentException.class);
            lanza(() -> EntradaProyectoIr.desdeResultados(Arrays.asList(valido.get(0), valido.get(0))), IllegalArgumentException.class);
            valido.get(0).dependencias.add(Path.of("/tmp/ir-ausente.txt"));
            lanza(() -> EntradaProyectoIr.desdeResultados(valido), IllegalArgumentException.class);
            for (String fuente : Arrays.asList("{ class Main { public main() { int x = ; } } }",
                    "{ class Main { public main() { int x = true; } } }", "{ class Main { public main() { @ } } }")) {
                List<Main.ResultadoAnalisis> invalido = Main.analizarProyecto(Path.of("/tmp/ir-invalido.txt"), fuente, false);
                lanza(() -> EntradaProyectoIr.desdeResultados(invalido), IllegalArgumentException.class);
            }
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
