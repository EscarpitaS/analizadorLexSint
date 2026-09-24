import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class AnalizadorSintactico {
    private final List<Token> tokens;
    private final Gramatica gramatica;
    private final List<compiladorErrores> errores = new ArrayList<>();
    private final List<EntradaTablaSimbolos> tablaSimbolos = new ArrayList<>();
    private int actual = 0;
    private final java.util.Deque<Integer> erroresDeConstruccion = new java.util.ArrayDeque<>();
    private int mainsPublicos = 0;
    private NodoArbolSintactico arbol;

    AnalizadorSintactico(List<Token> tokens, Gramatica gramatica) {
        this.tokens = tokens;
        this.gramatica = gramatica;
    }

    void analizar() {
        actual = 0;
        errores.clear();
        tablaSimbolos.clear();
        erroresDeConstruccion.clear();
        arbol = nodo(gramatica.getSimboloInicial(), "", actual());
        mainsPublicos = 0;

        while (coincide("import")) {
            arbol.agregar(importacion());
        }

        if (!verificar("{")) {
            error(actual(), "Se esperaba '{' para iniciar el bloque global.",
                    "La estructura del programa es: imports opcionales seguidos de { clases }.");
            return;
        }
        Token aperturaGlobal = avanzar();
        NodoArbolSintactico bloqueGlobal = nodo("BLOQUE_GLOBAL", "", aperturaGlobal);
        arbol.agregar(bloqueGlobal);

        int clases = 0;
        while (!fin() && !verificar("}")) {
            if (esInicioClase()) {
                bloqueGlobal.agregar(clase());
                clases++;
            } else {
                error(actual(), "Solo se permiten clases dentro del bloque global.",
                        "Declara una clase con: public class Nombre { ... }.");
                sincronizarBloqueGlobal();
            }
        }

        if (clases == 0) {
            error(actual(), "El programa debe contener al menos una clase.",
                    "Agrega una declaracion como: public class Main { ... }.");
        }

        if (mainsPublicos == 0) {
            error(actual(), "El programa debe contener un metodo 'public main()'.",
                    "Declara el punto de entrada dentro de una clase: public main() { ... }.");
        }

        consumir("}", "Se esperaba '}' para cerrar el bloque global.");

        if (!fin()) {
            error(actual(), "Hay tokens despues del cierre del bloque global.",
                    "Las importaciones van al inicio y el programa termina despues de la llave global de cierre.");
        }
    }

    private void sincronizarBloqueGlobal() {
        while (!fin() && !verificar("}") && !esInicioClase()) {
            avanzar();
        }
    }

    private NodoArbolSintactico importacion() {
        erroresDeConstruccion.push(0);
        NodoArbolSintactico resultado;
        int erroresLocales;
        try { resultado = importacionInterna(); }
        finally { erroresLocales = erroresDeConstruccion.pop(); }
        if (erroresLocales > 0) resultado.marcarSintaxisIncompleta();
        return resultado;
    }

    private NodoArbolSintactico importacionInterna() {
        Token palabraImport = anterior();
        NodoArbolSintactico nodo = nodo("IMPORTACION", "", palabraImport);
        nodo.agregar(hoja(palabraImport));

        Token primero = consumirTipo(tipoToken.IDENTIFICADOR,
                "Se esperaba el primer identificador de la ruta importada.");
        StringBuilder ruta = new StringBuilder(primero.lexema);
        while (coincide(".")) {
            Token parte = consumirTipo(tipoToken.IDENTIFICADOR,
                    "Se esperaba un identificador despues de '.' en la ruta importada.");
            ruta.append('.').append(parte.lexema);
        }
        nodo.agregar(nodo("RUTA", ruta.toString(), primero));
        if (!verificar(";")) {
            error(actual(), "Se esperaba ';' al final de la importacion.",
                    "Termina la ruta importada con ';' antes de iniciar el programa.");
            sincronizarImportacion();
        }
        if (verificar(";")) avanzar();
        return nodo;
    }

    private void sincronizarImportacion() {
        while (!fin() && !verificar(";") && !verificar("{") && !verificar("}")) {
            avanzar();
        }
    }

    private NodoArbolSintactico sentencia() {
        erroresDeConstruccion.push(0);
        NodoArbolSintactico resultado;
        int erroresLocales;
        try { resultado = sentenciaInterna(); }
        finally { erroresLocales = erroresDeConstruccion.pop(); }
        if (erroresLocales > 0) resultado.marcarSintaxisIncompleta();
        return resultado;
    }

    private NodoArbolSintactico sentenciaInterna() {
        if (coincide("if")) return condicional();
        if (coincide("repeat")) return cicloRepeat();
        if (coincide("while")) return cicloWhile();
        if (coincide("Exist")) return exist();
        if (coincide("return")) return retorno();

        if (esInicioDeclaracion()) return declaracion();
        if (verificarTipo(tipoToken.IDENTIFICADOR)) return asignacionOLlamada();

        Token token = avanzar();
        error(token, "Sentencia no reconocida.",
                "Se esperaba declaracion, asignacion, llamada, condicion, ciclo o return.");
        sincronizar();
        return new NodoArbolSintactico("ERROR", token.lexema);
    }

    private NodoArbolSintactico bloque() {
        Token apertura = consumir("{", "Se esperaba '{' para iniciar el bloque.");
        return bloqueDesdeApertura(apertura);
    }

    private NodoArbolSintactico bloqueDesdeApertura(Token apertura) {
        NodoArbolSintactico nodo = nodo("BLOQUE", "", apertura);
        while (!fin() && !verificar("}")) {
            nodo.agregar(sentencia());
        }
        consumir("}", "Se esperaba '}' para cerrar el bloque.");
        return nodo;
    }

    private NodoArbolSintactico clase() {
        erroresDeConstruccion.push(0);
        NodoArbolSintactico resultado;
        int erroresLocales;
        try { resultado = claseInterna(); }
        finally { erroresLocales = erroresDeConstruccion.pop(); }
        if (erroresLocales > 0) resultado.marcarSintaxisIncompleta();
        return resultado;
    }

    private NodoArbolSintactico claseInterna() {
        NodoArbolSintactico nodo = nodo("CLASE", "", actual());
        if (gramatica.esModificador(actual().lexema)) nodo.agregar(hoja(avanzar()));
        consumir("class", "Se esperaba la palabra clave 'class'.");
        nodo.agregar(new NodoArbolSintactico("PALABRA_CLAVE", "class"));

        Token nombre = consumirTipo(tipoToken.IDENTIFICADOR, "Se esperaba el nombre de la clase.");
        nodo.agregar(nodo("IDENTIFICADOR", nombre));
        registrar(nombre, "class", "Clase");

        if (coincide("extends")) {
            NodoArbolSintactico herencia = new NodoArbolSintactico("HERENCIA");
            Token base = consumirTipo(tipoToken.IDENTIFICADOR, "Se esperaba la clase base despues de 'extends'.");
            herencia.agregar(new NodoArbolSintactico("IDENTIFICADOR", base.lexema));
            nodo.agregar(herencia);
        }

        nodo.agregar(bloqueClase());
        return nodo;
    }

    private NodoArbolSintactico bloqueClase() {
        Token apertura = consumir("{", "Se esperaba '{' para iniciar el cuerpo de la clase.");
        NodoArbolSintactico nodo = nodo("CUERPO_CLASE", "", apertura);

        while (!fin() && !verificar("}")) {
            nodo.agregar(miembro());
        }

        consumir("}", "Se esperaba '}' para cerrar el cuerpo de la clase.");
        return nodo;
    }

    private NodoArbolSintactico miembro() {
        erroresDeConstruccion.push(0);
        NodoArbolSintactico resultado;
        int erroresLocales;
        try { resultado = miembroInterna(); }
        finally { erroresLocales = erroresDeConstruccion.pop(); }
        if (erroresLocales > 0) resultado.marcarSintaxisIncompleta();
        return resultado;
    }

    private NodoArbolSintactico miembroInterna() {
        NodoArbolSintactico nodo = new NodoArbolSintactico("MIEMBRO");
        String modificador = null;
        if (gramatica.esModificador(actual().lexema)) {
            Token tokenModificador = avanzar();
            modificador = tokenModificador.lexema;
            nodo.agregar(hoja(tokenModificador));
        }

        if (coincide("main")) {
            Token main = anterior();
            nodo.agregar(nodo("METODO", "main", main));
            if (!"public".equals(modificador)) {
                error(main, "El punto de entrada debe declararse como 'public main()'.",
                        "Usa: public main() { ... }.");
            } else {
                mainsPublicos++;
            }
            registrar(main, "void", "Metodo");
            if (!coincide("(")) {
                error(actual(), "Se esperaba '(' despues de 'main'.",
                        "El punto de entrada debe escribirse como: public main() { ... }.");
                sincronizarCabeceraMetodo();
                if (verificar("{")) nodo.agregar(bloque());
                return nodo;
            }
            consumir(")", "Se esperaba ')' despues de 'main('.");
            nodo.agregar(bloque());
            return nodo;
        }

        if (coincide("void")) {
            Token nombre = consumirTipo(tipoToken.IDENTIFICADOR,
                    "Se esperaba el nombre del metodo despues de 'void'.");
            return miembroMetodo(nodo, "void", nombre);
        }

        if (esInicioTipoMiembro()) {
            String tipo = parsearTipoStr();
            Token nombre = consumirTipo(tipoToken.IDENTIFICADOR,
                    "Se esperaba el nombre del campo o metodo.");

            if (verificar("(")) {
                return miembroMetodo(nodo, tipo, nombre);
            }

            nodo.agregar(new NodoArbolSintactico("CAMPO", tipo));
            nodo.agregar(nodo("IDENTIFICADOR", nombre));
            registrar(nombre, tipo, "Campo");
            if (coincide("=")) {
                NodoArbolSintactico inicializacion = new NodoArbolSintactico("INICIALIZACION", "=");
                inicializacion.agregar(expresion(0));
                nodo.agregar(inicializacion);
            }
            terminarMiembro("Se esperaba ';' al final de la declaracion de campo.");
            return nodo;
        }

        Token inesperado = avanzar();
        error(inesperado, "Miembro de clase no reconocido.",
                "Se esperaba un campo, un metodo o 'public main()'.");
        sincronizarMiembroClase();
        return new NodoArbolSintactico("ERROR", inesperado.lexema);
    }

    /**
     * Descarta el resto de un miembro invalido sin invadir el siguiente miembro
     * valido ni la llave que cierra la clase.
     */
    private void sincronizarMiembroClase() {
        while (!fin() && !verificar("}") && !esInicioMiembroClase()) {
            if (verificar(";")) {
                avanzar();
                return;
            }
            avanzar();
        }
    }

    private boolean esInicioMiembroClase() {
        int indice = actual;
        if (indice < tokens.size() && gramatica.esModificador(tokens.get(indice).lexema)) indice++;
        if (indice >= tokens.size()) return false;

        Token token = tokens.get(indice);
        if (token.lexema.equals("main") || token.lexema.equals("void") || gramatica.esTipoDato(token.lexema)) {
            return true;
        }
        return token.tipo == tipoToken.IDENTIFICADOR
                && indice + 1 < tokens.size()
                && tokens.get(indice + 1).tipo == tipoToken.IDENTIFICADOR;
    }

    private NodoArbolSintactico miembroMetodo(NodoArbolSintactico nodo, String tipoRetorno, Token nombre) {
        NodoArbolSintactico metodo = nodo("METODO", nombre);
        metodo.agregar(nodo("RETORNO", tipoRetorno, nombre));
        registrar(nombre, tipoRetorno, "Metodo");

        if (!coincide("(")) {
            error(actual(), "Se esperaba '(' en la declaracion del metodo.",
                    "La cabecera debe escribirse como: Tipo nombre(parametros) { ... }.");
            sincronizarCabeceraMetodo();
            if (verificar("{")) metodo.agregar(bloque());
            nodo.agregar(metodo);
            return nodo;
        }
        NodoArbolSintactico parametros = new NodoArbolSintactico("PARAMETROS");
        while (!fin() && !verificar(")")) {
            if (!esInicioTipoMiembro()) {
                error(actual(), "Se esperaba el tipo de un parametro.",
                        "Un parametro tiene la forma: Tipo nombre.");
                avanzar();
                continue;
            }
            String tipo = parsearTipoStr();
            Token parametro = consumirTipo(tipoToken.IDENTIFICADOR,
                    "Se esperaba el nombre del parametro.");
            parametros.agregar(nodo("PARAMETRO", tipo + " " + parametro.lexema, parametro));
            registrar(parametro, tipo, "Parametro");
            if (coincide(",")) {
                if (verificar(")")) {
                    error(anterior(), "No se permite una coma final en los parametros.",
                            "Agrega otro parametro o elimina la coma antes de ')'.");
                    break;
                }
            } else if (!verificar(")")) {
                error(actual(), "Se esperaba ',' o ')' despues de un parametro.",
                        "Separa los parametros con ',' o cierra la lista con ')'.");
                sincronizarLista(")");
                break;
            } else break;
        }
        consumir(")", "Se esperaba ')' para cerrar los parametros del metodo.");
        metodo.agregar(parametros);
        metodo.agregar(bloque());
        nodo.agregar(metodo);
        return nodo;
    }

    /** Recupera una cabecera de método incompleta hasta su bloque asociado. */
    private void sincronizarCabeceraMetodo() {
        while (!fin() && !verificar("{") && !verificar("}") && !verificar(";")) {
            avanzar();
        }
    }

    private boolean esInicioTipoMiembro() {
        return !fin() && (gramatica.esTipoDato(actual().lexema)
                || verificarTipo(tipoToken.IDENTIFICADOR));
    }

    private NodoArbolSintactico declaracion() {
        NodoArbolSintactico nodo = new NodoArbolSintactico("DECLARACION");

        // Reglas D.1 y D.2: var/let ID = Expr ;
        if (verificar("var") || verificar("let")) {
            String kw = avanzar().lexema;
            nodo.agregar(new NodoArbolSintactico("TIPO", kw));
            Token nombre = consumirTipo(tipoToken.IDENTIFICADOR,
                    "Se esperaba un identificador despues de '" + kw + "'.");
            nodo.agregar(nodo("IDENTIFICADOR", nombre));
            registrar(nombre, kw, "Variable");
            consumir("=", "Se esperaba '=' en la declaracion con '" + kw + "'.");
            NodoArbolSintactico asigna = new NodoArbolSintactico("INICIALIZACION", "=");
            asigna.agregar(expresion(0));
            nodo.agregar(asigna);
            terminarSentencia("Se esperaba ';' al final de la declaracion.");
            return nodo;
        }

        // Regla D.3: Tipo ID DeclResto ;  (Tipo puede ser list<Tipo>)
        String tipo = parsearTipoStr();
        nodo.agregar(new NodoArbolSintactico("TIPO", tipo));

        Token nombre = consumirTipo(tipoToken.IDENTIFICADOR, "Se esperaba un identificador en la declaracion.");
        nodo.agregar(nodo("IDENTIFICADOR", nombre));
        registrar(nombre, tipo, "Variable");

        if (coincide("=")) {
            NodoArbolSintactico asigna = new NodoArbolSintactico("INICIALIZACION", "=");
            asigna.agregar(expresion(0));
            nodo.agregar(asigna);
        }

        terminarSentencia("Se esperaba ';' al final de la declaracion.");
        return nodo;
    }

    /** Parsea Tipo → TipoBase | ID, incluyendo list<Tipo> recursivo. */
    private String parsearTipoStr() {
        String tipo;
        if (verificar("list")) {
            avanzar(); // consume "list"
            consumir("<", "Se esperaba '<' despues de 'list'.");
            String interno = parsearTipoStr();
            consumir(">", "Se esperaba '>' para cerrar 'list<...>'.");
            tipo = "list<" + interno + ">";
        } else if (gramatica.esTipoDato(actual().lexema) || verificarTipo(tipoToken.IDENTIFICADOR)) {
            tipo = avanzar().lexema;
        } else {
            Token inesperado = avanzar();
            error(inesperado, "Tipo no valido.",
                    "Se esperaba int, float, string, bool, list<Tipo> o un tipo definido por el usuario.");
            tipo = "<tipo-invalido>";
        }
        return tipo;
    }

    private NodoArbolSintactico asignacionOLlamada() {
        Token nombre = avanzar();
        NodoArbolSintactico destino = aplicarSufijos(nodo("IDENTIFICADOR", nombre));

        if (gramatica.esOperadorAsignacion(actual().lexema)) {
            Token operador = avanzar();
            NodoArbolSintactico nodo = new NodoArbolSintactico("ASIGNACION");
            nodo.agregar(destino);
            nodo.agregar(nodo("OPERADOR", operador));
            nodo.agregar(expresion(0));
            terminarSentencia("Se esperaba ';' al final de la asignacion.");
            return nodo;
        }

        if (coincide(";")) {
            NodoArbolSintactico nodo = new NodoArbolSintactico("SENTENCIA_EXPRESION");
            nodo.agregar(destino);
            return nodo;
        }

        terminarSentencia("Se esperaba una asignacion o ';' despues del identificador.");
        return new NodoArbolSintactico("ERROR", nombre.lexema);
    }

    private NodoArbolSintactico condicional() {
        NodoArbolSintactico nodo = nodo("CONDICIONAL", "if", anterior());
        if (!coincide("(")) {
            error(actual(), "Se esperaba '(' despues de 'if'.",
                    "La condicion debe escribirse entre parentesis antes del bloque.");
            sincronizarCabeceraControl();
            if (verificar("{")) nodo.agregar(bloque());
            return nodo;
        }
        nodo.agregar(expresion(0));
        consumir(")", "Se esperaba ')' despues de la condicion.");
        nodo.agregar(bloque());

        if (coincide("else")) {
            NodoArbolSintactico otro = new NodoArbolSintactico("ELSE");
            if (coincide("if")) otro.agregar(condicional());
            else otro.agregar(bloque());
            nodo.agregar(otro);
        }
        return nodo;
    }

    /** Recupera una cabecera de control incompleta hasta el bloque asociado. */
    private void sincronizarCabeceraControl() {
        while (!fin() && !verificar("{") && !verificar("}") && !verificar(";")) {
            avanzar();
        }
    }

    private NodoArbolSintactico cicloRepeat() {
        NodoArbolSintactico nodo = nodo("CICLO", "repeat", anterior());

        // RResto → each ( Tipo ID in Expr ) Bloque   (CL.4)
        if (coincide("each")) {
            nodo.agregar(new NodoArbolSintactico("PALABRA_CLAVE", "each"));
            if (!coincide("(")) {
                error(actual(), "Se esperaba '(' despues de 'repeat each'.",
                        "La iteracion debe escribirse como: repeat each ( Tipo item in lista ) { ... }.");
                sincronizarCabeceraControl();
                if (verificar("{")) nodo.agregar(bloque());
                return nodo;
            }
            if (esInicioTipoMiembro()) {
                nodo.agregar(new NodoArbolSintactico("TIPO", parsearTipoStr()));
            } else {
                error(actual(), "Se esperaba un tipo de dato en 'repeat each'.",
                        "Ejemplo: repeat each ( int item in lista ) { ... }");
            }
            Token id = consumirTipo(tipoToken.IDENTIFICADOR,
                    "Se esperaba el identificador del elemento en 'repeat each'.");
            nodo.agregar(nodo("IDENTIFICADOR", id));
            registrar(id, "iterador", "Variable");
            consumir("in", "Se esperaba 'in' dentro de 'repeat each'.");
            nodo.agregar(expresion(0));
            consumir(")", "Se esperaba ')' para cerrar 'repeat each'.");
            nodo.agregar(bloque());
            return nodo;
        }

        // RepeatResto → ( Expr ) Bloque
        if (!coincide("(")) {
            error(actual(), "Se esperaba '(' o 'each' despues de 'repeat'.",
                    "Usa repeat ( condicion ) { ... } o repeat each ( Tipo item in lista ) { ... }.");
            sincronizarCabeceraControl();
            if (verificar("{")) nodo.agregar(bloque());
            return nodo;
        }
        nodo.agregar(expresion(0));
        consumir(")", "Se esperaba ')' despues de repeat.");
        nodo.agregar(bloque());
        return nodo;
    }

    private NodoArbolSintactico cicloWhile() {
        NodoArbolSintactico nodo = nodo("CICLO", "while", anterior());
        if (!coincide("(")) {
            error(actual(), "Se esperaba '(' despues de 'while'.",
                    "La condicion debe escribirse entre parentesis antes del bloque.");
            sincronizarCabeceraControl();
            if (verificar("{")) nodo.agregar(bloque());
            return nodo;
        }
        nodo.agregar(expresion(0));
        consumir(")", "Se esperaba ')' despues de while.");
        nodo.agregar(bloque());
        return nodo;
    }

    private NodoArbolSintactico exist() {
        NodoArbolSintactico nodo = nodo("EXIST", "Exist", anterior());
        if (!coincide("(")) {
            error(actual(), "Se esperaba '(' despues de 'Exist'.",
                    "La condicion debe escribirse entre parentesis antes del bloque.");
            sincronizarCabeceraControl();
            if (verificar("{")) nodo.agregar(bloque());
            return nodo;
        }
        nodo.agregar(expresion(0));
        consumir(")", "Se esperaba ')' despues de Exist.");
        nodo.agregar(bloque());
        return nodo;
    }

    private NodoArbolSintactico retorno() {
        NodoArbolSintactico nodo = nodo("RETORNO", "return", anterior());
        if (!verificar(";")) nodo.agregar(expresion(0));
        terminarSentencia("Se esperaba ';' despues de return.");
        return nodo;
    }

    private NodoArbolSintactico expresion(int minPrecedencia) {
        return expresionOr();
    }

    private NodoArbolSintactico expresionOr() {
        NodoArbolSintactico izquierda = expresionAnd();
        while (coincide("||")) {
            NodoArbolSintactico nodo = new NodoArbolSintactico("EXPRESION", "||");
            nodo.agregar(izquierda);
            nodo.agregar(expresionAnd());
            izquierda = nodo;
        }
        return izquierda;
    }

    private NodoArbolSintactico expresionAnd() {
        NodoArbolSintactico izquierda = expresionNot();
        while (coincide("&&")) {
            NodoArbolSintactico nodo = new NodoArbolSintactico("EXPRESION", "&&");
            nodo.agregar(izquierda);
            nodo.agregar(expresionNot());
            izquierda = nodo;
        }
        return izquierda;
    }

    private NodoArbolSintactico expresionNot() {
        if (coincide("not")) {
            NodoArbolSintactico nodo = new NodoArbolSintactico("UNARIO", "not");
            nodo.agregar(expresionNot());
            return nodo;
        }
        return expresionComparacion();
    }

    private NodoArbolSintactico expresionComparacion() {
        NodoArbolSintactico izquierda = expresionSuma();
        if (!fin() && esOperadorComparacion(actual().lexema)) {
            Token operador = avanzar();
            NodoArbolSintactico nodo = nodo("EXPRESION", operador);
            nodo.agregar(izquierda);
            nodo.agregar(expresionSuma());
            return nodo;
        }
        return izquierda;
    }

    private NodoArbolSintactico expresionSuma() {
        NodoArbolSintactico izquierda = expresionProducto();
        while (verificar("+") || verificar("-")) {
            Token operador = avanzar();
            NodoArbolSintactico nodo = nodo("EXPRESION", operador);
            nodo.agregar(izquierda);
            nodo.agregar(expresionProducto());
            izquierda = nodo;
        }
        return izquierda;
    }

    private NodoArbolSintactico expresionProducto() {
        NodoArbolSintactico izquierda = unario();
        while (verificar("*") || verificar("/")) {
            Token operador = avanzar();
            NodoArbolSintactico nodo = nodo("EXPRESION", operador);
            nodo.agregar(izquierda);
            nodo.agregar(unario());
            izquierda = nodo;
        }
        return izquierda;
    }

    private boolean esOperadorComparacion(String lexema) {
        return lexema.equals("==") || lexema.equals("!=")
                || lexema.equals("<") || lexema.equals("<=")
                || lexema.equals(">") || lexema.equals(">=");
    }

    private NodoArbolSintactico unario() {
        if (!fin() && gramatica.esOperadorUnario(actual().lexema)) {
            Token operador = avanzar();
            NodoArbolSintactico nodo = nodo("UNARIO", operador);
            nodo.agregar(unario());
            return nodo;
        }
        return primario();
    }

    private NodoArbolSintactico primario() {
        if (coincide("(")) {
            NodoArbolSintactico nodo = nodo("AGRUPACION", "", anterior());
            nodo.agregar(expresion(0));
            consumir(")", "Se esperaba ')' para cerrar la expresion.");
            return nodo;
        }

        // Literal de lista: [ ArgumentosOpt ] (Regla E.15)
        if (coincide("[")) {
            NodoArbolSintactico nodo = nodo("ARREGLO_LITERAL", "", anterior());
            while (!fin() && !verificar("]")) {
                nodo.agregar(expresion(0));
                if (coincide(",")) {
                    if (verificar("]")) {
                        error(anterior(), "No se permite una coma final en un arreglo.",
                                "Agrega otro elemento o elimina la coma antes de ']'.");
                        break;
                    }
                } else if (!verificar("]")) {
                    error(actual(), "Se esperaba ',' o ']' despues de un elemento de arreglo.",
                            "Separa los elementos con ',' o cierra el arreglo con ']'.");
                    sincronizarLista("]");
                    break;
                } else break;
            }
            consumir("]", "Se esperaba ']' para cerrar el arreglo.");
            return nodo;
        }

        if (coincide("new")) {
            NodoArbolSintactico nodo = nodo("NEW", "new", anterior());
            Token nombre = consumirTipo(tipoToken.IDENTIFICADOR,
                    "Se esperaba el nombre de una clase despues de 'new'.");
            nodo.agregar(nodo("IDENTIFICADOR", nombre));
            consumir("(", "Se esperaba '(' despues del tipo construido con 'new'.");
            nodo.agregar(argumentosDesdeApertura());
            return nodo;
        }

        if (verificarTipo(tipoToken.IDENTIFICADOR)) {
            Token id = avanzar();
            return aplicarSufijos(nodo("IDENTIFICADOR", id));
        }

        if (verificarTipo(tipoToken.ENTERO) || verificarTipo(tipoToken.FLOAT)
                || verificarTipo(tipoToken.STRING)) {
            return hoja(avanzar());
        }

        if (coincide("true") || coincide("false") || coincide("null")) {
            return hoja(anterior());
        }

        // Los delimitadores cierran la construcción exterior. No se consumen
        // aquí: declaración, llamada, arreglo o bloque podrá recuperarse y
        // continuar con el siguiente elemento.
        if (verificar(";") || verificar(")") || verificar("]")
                || verificar("}") || verificar(",")) {
            Token token = actual();
            error(token, "Expresion incompleta o no valida.",
                    "Se esperaba identificador, literal o expresion entre parentesis.");
            return nodo("ERROR_EXPRESION", token);
        }

        Token token = avanzar();
        error(token, "Expresion incompleta o no valida.", "Se esperaba identificador, literal o expresion entre parentesis.");
        return nodo("ERROR_EXPRESION", token);
    }

    private NodoArbolSintactico aplicarSufijos(NodoArbolSintactico base) {
        NodoArbolSintactico actual = base;
        while (!fin()) {
            if (coincide("(")) {
                NodoArbolSintactico llamada = nodo("LLAMADA", "", anterior());
                llamada.agregar(actual);
                llamada.agregar(argumentosDesdeApertura());
                actual = llamada;
            } else if (coincide("[")) {
                NodoArbolSintactico acceso = nodo("ACCESO_ARREGLO", "", anterior());
                acceso.agregar(actual);
                acceso.agregar(expresion(0));
                consumir("]", "Se esperaba ']' para cerrar el acceso al arreglo.");
                actual = acceso;
            } else if (coincide(".")) {
                Token miembro = consumirTipo(tipoToken.IDENTIFICADOR,
                        "Se esperaba un identificador de miembro despues de '.'.");
                NodoArbolSintactico acceso = nodo("ACCESO_MIEMBRO", miembro);
                acceso.agregar(actual);
                actual = acceso;
            } else {
                break;
            }
        }
        return actual;
    }

    private NodoArbolSintactico argumentosDesdeApertura() {
        NodoArbolSintactico args = new NodoArbolSintactico("ARGUMENTOS");
        while (!fin() && !verificar(")")) {
            args.agregar(expresion(0));
            if (coincide(",")) {
                if (verificar(")")) {
                    error(anterior(), "No se permite una coma final en los argumentos.",
                            "Agrega otro argumento o elimina la coma antes de ')'.");
                    break;
                }
            } else if (!verificar(")")) {
                error(actual(), "Se esperaba ',' o ')' despues de un argumento.",
                        "Separa los argumentos con ',' o cierra la llamada con ')'.");
                sincronizarLista(")");
                break;
            } else break;
        }
        consumir(")", "Se esperaba ')' al cerrar los argumentos.");
        return args;
    }

    private void sincronizarLista(String cierre) {
        while (!fin() && !verificar(cierre) && !verificar(";") && !verificar("}")) {
            avanzar();
        }
    }

    /** Finaliza una sentencia sin consumir el inicio de la siguiente válida. */
    private void terminarSentencia(String mensaje) {
        if (coincide(";")) return;

        error(actual(), mensaje, "Token encontrado: '" + actual().lexema + "'.");
        while (!fin() && !verificar("}") && !esInicioSentencia()) {
            if (coincide(";")) return;
            avanzar();
        }
    }

    /** Finaliza un campo sin consumir el siguiente miembro válido de la clase. */
    private void terminarMiembro(String mensaje) {
        if (coincide(";")) return;

        error(actual(), mensaje, "Token encontrado: '" + actual().lexema + "'.");
        sincronizarMiembroClase();
    }

    private boolean esInicioSentencia() {
        return verificar("if") || verificar("repeat") || verificar("while")
                || verificar("Exist") || verificar("return")
                || esInicioDeclaracion() || verificarTipo(tipoToken.IDENTIFICADOR);
    }

    private boolean esInicioClase() {
        int i = actual;
        if (i < tokens.size() && gramatica.esModificador(tokens.get(i).lexema)) i++;
        return i < tokens.size() && tokens.get(i).lexema.equals("class");
    }

    private boolean esInicioDeclaracion() {
        if (fin()) return false;
        Token t = actual();
        if (t.tipo == tipoToken.IDENTIFICADOR) {
            return actual + 1 < tokens.size() && tokens.get(actual + 1).tipo == tipoToken.IDENTIFICADOR;
        }

        if (t.tipo != tipoToken.PALABRA_CLAVE) return false;
        // var y let inician declaracion (D.1, D.2)
        if (t.lexema.equals("var") || t.lexema.equals("let")) return true;
        // tipos de dato: int, float, string, bool, list (T.1, T.2)
        return gramatica.esTipoDato(t.lexema);
    }

    private void registrar(Token token, String tipo, String rol) {
        if (token.tipo == tipoToken.EOF) return;
        tablaSimbolos.add(new EntradaTablaSimbolos(token.lexema, tipo, rol, token.linea, token.columna));
    }

    private NodoArbolSintactico hoja(Token token) {
        return new NodoArbolSintactico(token.tipo.toString(), token.lexema, token.linea, token.columna);
    }

    private NodoArbolSintactico nodo(String nombre, Token token) {
        return nodo(nombre, token.lexema, token);
    }

    private NodoArbolSintactico nodo(String nombre, String valor, Token token) {
        return new NodoArbolSintactico(nombre, valor, token.linea, token.columna);
    }

    private boolean coincide(String lexema) {
        if (!verificar(lexema)) return false;
        avanzar();
        return true;
    }

    private boolean verificar(String lexema) {
        return !fin() && actual().lexema.equals(lexema);
    }

    private boolean verificarTipo(tipoToken tipo) {
        return !fin() && actual().tipo == tipo;
    }

    private Token consumir(String lexema, String mensaje) {
        if (verificar(lexema)) return avanzar();
        error(actual(), mensaje, "Token encontrado: '" + actual().lexema + "'.");
        return new Token(tipoToken.DESCONOCIDO, lexema, actual().linea, actual().columna);
    }

    private Token consumirTipo(tipoToken tipo, String mensaje) {
        if (verificarTipo(tipo)) return avanzar();
        error(actual(), mensaje, "Token encontrado: '" + actual().lexema + "'.");
        return new Token(tipo, actual().lexema, actual().linea, actual().columna);
    }

    private Token avanzar() {
        if (!fin()) actual++;
        return anterior();
    }

    private Token actual() {
        if (actual >= tokens.size()) return tokens.get(tokens.size() - 1);
        return tokens.get(actual);
    }

    private Token anterior() {
        return tokens.get(Math.max(0, actual - 1));
    }

    private boolean fin() {
        return actual >= tokens.size() || tokens.get(actual).tipo == tipoToken.EOF;
    }

    private void error(Token token, String mensaje, String pista) {
        if (arbol != null) arbol.marcarSintaxisIncompleta();
        if (!erroresDeConstruccion.isEmpty()) erroresDeConstruccion.push(erroresDeConstruccion.pop() + 1);
        errores.add(new compiladorErrores(
                compiladorErrores.TIPO_ERROR.SINTACTICO,
                token.linea,
                token.columna,
                token.lexema,
                mensaje,
                pista
        ));
    }

    private void sincronizar() {
        while (!fin()) {
            if (anterior().lexema.equals(";") || anterior().lexema.equals("}")) return;
            if (verificar("}") || verificar("if") || verificar("repeat")
                    || verificar("while") || verificar("Exist") || verificar("return")
                    || esInicioDeclaracion()) return;
            avanzar();
        }
    }

    NodoArbolSintactico getArbol() {
        return arbol;
    }

    List<compiladorErrores> getErrores() {
        return Collections.unmodifiableList(errores);
    }

    boolean hasErrors() {
        return !errores.isEmpty();
    }

    List<EntradaTablaSimbolos> getTablaSimbolos() {
        return Collections.unmodifiableList(tablaSimbolos);
    }
}

class EntradaTablaSimbolos {
    final String nombre;
    final String tipo;
    final String rol;
    final int linea;
    final int columna;

    EntradaTablaSimbolos(String nombre, String tipo, String rol, int linea, int columna) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.rol = rol;
        this.linea = linea;
        this.columna = columna;
    }
}
