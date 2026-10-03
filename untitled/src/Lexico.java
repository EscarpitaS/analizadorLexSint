import java.util.*;

public class Lexico {

    // 26 categorias del alfabeto
    private static final int C_UPPER  = 0;   // A-Z
    private static final int C_LOWER  = 1;   // a-z
    private static final int C_DIGIT  = 2;   // 0-9
    private static final int C_LBRACE = 3;   // {
    private static final int C_RBRACE = 4;   // }
    private static final int C_ASSIGN = 5;   // =
    private static final int C_LT     = 6;   // <
    private static final int C_GT     = 7;   // >
    private static final int C_LPAREN = 8;   // (
    private static final int C_RPAREN = 9;   // )
    private static final int C_DOT    = 10;  // .
    private static final int C_LBRACK = 11;  // [
    private static final int C_RBRACK = 12;  // ]
    private static final int C_BANG   = 13;  // !
    private static final int C_SEMI   = 14;  // ;
    private static final int C_QUOTE  = 15;  // "
    private static final int C_PLUS   = 16;  // +
    private static final int C_MINUS  = 17;  // -
    private static final int C_STAR   = 18;  // *
    private static final int C_SLASH  = 19;  // /
    private static final int C_AMP    = 20;  // &
    private static final int C_PIPE   = 21;  // |
    private static final int C_COMMA  = 22;  // ,
    private static final int C_SPACE  = 23;  // espacio \t \r
    private static final int C_NEWLN  = 24;  // \n
    private static final int C_OTHER  = 25;  // cualquier otro

    private static final int NUM_CATS = 26;

    //estados
    private static final int S0   = 0;   // inicio
    private static final int S_ID = 1;   // leyendo id / keyword  (q1/q9)
    private static final int S_IN = 2;   // leyendo entero        (q10)
    private static final int S_DT = 3;   // entero + punto        (q11 transicion)
    private static final int S_FL = 4;   // leyendo float         (q11 loop)
    private static final int S_ST = 5;   // dentro de string      (q16)
    private static final int S_EQ = 6;   // leyo =                (q4)
    private static final int S_LT = 7;   // leyo <                (q5)
    private static final int S_GT = 8;   // leyo >                (q6)
    private static final int S_BN = 9;   // leyo !                (q14)
    private static final int S_PL = 10;  // leyo +                (q17)
    private static final int S_MN = 11;  // leyo -                (q18)
    private static final int S_MU = 12;  // leyo *                (q19)
    private static final int S_SL = 13;  // leyo /                (q20)
    private static final int S_CM = 14;  // comentario //         (q20 loop)
    private static final int S_AM = 15;  // leyo &                (q21)
    private static final int S_PI = 16;  // leyo |                (q22)
    private static final int S_DL = 17;  // delimitador simple    (q2,q3,q7,q8,q12,q13,q15,q11coma)
    private static final int ERR  = 18;  // error lexico          (q23)

    private static final int NUM_STATES = 19;


    //  MATRIZ DE TRANSICIONES
    //  -1 = aceptar SIN consumir (releer caracter)
    //  -2 = aceptar consumiendo  (operador doble)
    // Columnas: UP  LO  DG LBR RBR  EQ  LT  GT  LP  RP DOT LBK RBK  BN SEM QUO  PL  MN  ST  SL  AM  PI COM SPC NLN OTH
    private static final int[][] T = {
            /*S0  ini*/{S_ID,S_ID,S_IN,S_DL,S_DL,S_EQ,S_LT,S_GT,S_DL,S_DL,S_DL,S_DL,S_DL,S_BN,S_DL,S_ST,S_PL,S_MN,S_MU,S_SL,S_AM,S_PI,S_DL, S0,  S0, ERR},
            /*S_ID id*/{S_ID,S_ID,S_ID,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1, ERR},
            /*S_IN en*/{  -1,  -1,S_IN,  -1,  -1,  -1,  -1,  -1,  -1,  -1,S_DT,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_DT dt*/{ERR, ERR,S_FL, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR},
            /*S_FL fl*/{  -1,  -1,S_FL,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_ST st*/{S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,  -2,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST,S_ST, ERR,S_ST},
            /*S_EQ = */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_LT < */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_GT > */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_BN ! */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_PL + */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_MN - */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_MU * */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_SL / */{  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,S_CM,  -1,  -1,  -1,  -1,  -1,  -1},
            /*S_CM //*/{S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,S_CM,  -1,S_CM},
            /*S_AM & */{  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1,  -1},
            /*S_PI | */{  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -2,  -1,  -1,  -1,  -1},
            /*S_DL dl*/{  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1,  -1},
            /*ERR    */{ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR, ERR,  S0,  S0, ERR},
    };

    // ── palabras clave de la gramatica ──────────────────────────────────
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "import",
            "if","else","Exist",
            "repeat","each","while","in",
            "class","extends","new","void","return",
            "public","private",
            "main",
            "var","let",
            "int","float","string","bool","list",
            "not","true","false",
            "null"
    ));

    /** El editor comparte el vocabulario del lexer, incluidas sus mayúsculas. */
    static boolean esPalabraClave(String texto) { return KEYWORDS.contains(texto); }
    static Set<String> palabrasClave() { return Collections.unmodifiableSet(KEYWORDS); }

    // estado interno
    private final String              source;
    private final List<Token>         tokens = new ArrayList<>();
    private final List<compiladorErrores> errors = new ArrayList<>();
    private int pos  = 0;
    private int line = 1;
    private int col  = 1;

    public Lexico(String source) {
        // LF, CRLF y CR representan el mismo límite de línea del lenguaje.
        this.source = source.replace("\r\n", "\n").replace('\r', '\n');
    }

    // empieza el bucle principal del adf con tokenizar()
    public void tokenizar() {
        tokens.clear();
        errors.clear();
        pos = 0;
        line = 1;
        col = 1;
        int state   = S0;
        StringBuilder lexeme = new StringBuilder();
        int tokLine = line, tokCol = col;

        while (pos <= source.length()) {

            // EOF
            if (pos == source.length()) {
                if (state == S_ST) {
                    errors.add(new compiladorErrores(
                            compiladorErrores.TIPO_ERROR.LEXICAL, tokLine, tokCol,
                            lexeme.toString(), "String sin cerrar",
                            "Agrega '\"' al final del string iniciado aqui."));
                } else if (state == S_DT) {
                    // Un punto después de un entero inicia un float. Al llegar
                    // a EOF sin decimales debe conservarse como error léxico.
                    reportarFloatSinDecimales(lexeme, tokLine, tokCol);
                } else if (state != S0) {
                    flush(state, lexeme, tokLine, tokCol, false);
                }
                tokens.add(new Token(tipoToken.EOF, "<EOF>", line, col));
                break;
            }

            char c   = source.charAt(pos);
            int  cat = category(c);

            // Diagnóstico específico para ':' fuera del alfabeto canónico.
            if (state == S0 && c == ':') {
                errors.add(new compiladorErrores(
                        compiladorErrores.TIPO_ERROR.LEXICAL, line, col,
                        ":", "Caracter no reconocido en el alfabeto",
                        "El lenguaje no usa ':' despues de condiciones ni bloques."));
                avanzar(c);
                tokLine = line;
                tokCol = col;
                continue;
            }

            // El contrato léxico no permite que un identificador inicie con
            // dígitos. Sin este caso, el DFA aceptaba "1variable" como dos
            // tokens (ENTERO + ID), ocultando el error de origen.
            if (state == S_IN && (cat == C_UPPER || cat == C_LOWER)) {
                consumirIdentificadorConInicioNumerico(lexeme, tokLine, tokCol);
                lexeme.setLength(0);
                state = S0;
                tokLine = line;
                tokCol = col;
                continue;
            }

            int  next = T[state][cat];

            // Aceptar SIN consumir
            if (next == -1) {
                flush(state, lexeme, tokLine, tokCol, false);
                lexeme.setLength(0);
                state = S0; tokLine = line; tokCol = col;
                continue;
            }

            // Aceptar consumiendo (operador doble o cierre string)
            if (next == -2) {
                lexeme.append(c);
                avanzar(c);
                flush(state, lexeme, tokLine, tokCol, true);
                lexeme.setLength(0);
                state = S0; tokLine = line; tokCol = col;
                continue;
            }

            // Error lexico
            if (next == ERR) {
                if (state == S_ST) {
                    errors.add(new compiladorErrores(compiladorErrores.TIPO_ERROR.LEXICAL,
                            tokLine, tokCol, lexeme.toString(), "String sin cerrar antes del fin de linea",
                            "Cierra la cadena en la misma linea con comillas dobles."));
                    lexeme.setLength(0);
                    avanzar(c);
                    state = S0; tokLine = line; tokCol = col;
                    continue;
                }
                if (state == S_DT) {
                    reportarFloatSinDecimales(lexeme, tokLine, tokCol);
                    lexeme.setLength(0);
                    state = S0; tokLine = line; tokCol = col;
                    continue;
                }

                if (lexeme.length() > 0 && state != S0)
                    flush(state, lexeme, tokLine, tokCol, false);
                lexeme.setLength(0);
                errors.add(new compiladorErrores(
                        compiladorErrores.TIPO_ERROR.LEXICAL, line, col,
                        String.valueOf(c), "Caracter no reconocido en el alfabeto",
                        construirSugerencia(c)));
                avanzar(c);
                state = S0; tokLine = line; tokCol = col;
                continue;
            }

            // Transicion normal
            // Espacios en S0: solo avanzar, no acumular en lexema
            if (state == S0 && (cat == C_SPACE || cat == C_NEWLN)) {
                avanzar(c);
                continue;
            }
            if (state == S0) { tokLine = line; tokCol = col; }
            lexeme.append(c);
            avanzar(c);
            state = next;

            // Delimitador simple: aceptar inmediatamente en S_DL
            if (state == S_DL) {
                flush(state, lexeme, tokLine, tokCol, false);
                lexeme.setLength(0);
                state = S0; tokLine = line; tokCol = col;
            }
        }
    }

    //  flush() — estado + lexema -> Token

    private void reportarFloatSinDecimales(StringBuilder sb, int tLine, int tCol) {
        String lex = sb.toString();
        if (lex.length() <= 1) return;

        String intPart = lex.substring(0, lex.length() - 1);
        tokens.add(new Token(tipoToken.ENTERO, intPart, tLine, tCol));
        errors.add(new compiladorErrores(
                compiladorErrores.TIPO_ERROR.LEXICAL,
                tLine,
                tCol + intPart.length(),
                ".",
                "Punto decimal sin digitos despues",
                "Si quieres declarar un float, escribe al menos un digito despues del punto, por ejemplo '" + intPart + ".0'."));
    }

    private void consumirIdentificadorConInicioNumerico(StringBuilder sb, int tLine, int tCol) {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (!Character.isLetterOrDigit(c)) break;
            sb.append(c);
            avanzar(c);
        }
        errors.add(new compiladorErrores(
                compiladorErrores.TIPO_ERROR.LEXICAL, tLine, tCol,
                sb.toString(), "Un identificador no puede iniciar con un digito",
                "Inicia el identificador con una letra, por ejemplo 'variable1'."));
    }

    private void flush(int state, StringBuilder sb, int tLine, int tCol, boolean consumed) {
        if (sb.length() == 0) return;
        String lex = sb.toString();

        switch (state) {

            case S_ID: {
                tipoToken kt = KEYWORDS.contains(lex)
                        ? tipoToken.PALABRA_CLAVE : tipoToken.IDENTIFICADOR;
                tokens.add(new Token(kt, lex, tLine, tCol));
                break;
            }

            case S_IN:
                tokens.add(new Token(tipoToken.ENTERO, lex, tLine, tCol));
                break;

            // Punto sin decimales: emitir INTEGER, devolver el punto
            case S_DT: {
                String intPart = lex.substring(0, lex.length() - 1);
                tokens.add(new Token(tipoToken.ENTERO, intPart, tLine, tCol));
                pos--;
                if (col > 1) col--;
                break;
            }

            case S_FL:
                tokens.add(new Token(tipoToken.FLOAT, lex, tLine, tCol));
                break;

            case S_ST:
                // consumed=true: lex ya incluye la comilla de cierre
                tokens.add(new Token(tipoToken.STRING, lex, tLine, tCol));
                break;

            // !, &, | — error si solos, validos si dobles (!=, &&, ||)
            case S_BN:
                if (consumed) {
                    // != — valido
                    tokens.add(new Token(tipoToken.OPERADOR, lex, tLine, tCol));
                } else {
                    errors.add(new compiladorErrores(
                            compiladorErrores.TIPO_ERROR.LEXICAL, tLine, tCol,
                            lex, "Operador '!' no valido solo",
                            construirSugerencia('!')));
                }
                break;
            case S_AM:
                if (consumed) {
                    // && — valido
                    tokens.add(new Token(tipoToken.OPERADOR, lex, tLine, tCol));
                } else {
                    errors.add(new compiladorErrores(
                            compiladorErrores.TIPO_ERROR.LEXICAL, tLine, tCol,
                            lex, "Operador '&' no valido solo",
                            construirSugerencia('&')));
                }
                break;
            case S_PI:
                if (consumed) {
                    // || — valido
                    tokens.add(new Token(tipoToken.OPERADOR, lex, tLine, tCol));
                } else {
                    errors.add(new compiladorErrores(
                            compiladorErrores.TIPO_ERROR.LEXICAL, tLine, tCol,
                            lex, "Operador '|' no valido solo",
                            construirSugerencia('|')));
                }
                break;

            // Operadores simples o compuestos (formados por -2)
            case S_EQ: case S_LT: case S_GT:
            case S_PL: case S_MN: case S_MU: case S_SL:
                tokens.add(new Token(tipoToken.OPERADOR, lex, tLine, tCol));
                break;

            case S_CM:
                // Comentarios se DESCARTAN segun la gramatica (pag. 6)
                // No se agregan a la lista de tokens
                break;

            case S_DL:
                tokens.add(new Token(tipoToken.DELIMITADOR, lex, tLine, tCol));
                break;

            default:
                tokens.add(new Token(tipoToken.DESCONOCIDO, lex, tLine, tCol));
                break;
        }
    }


    //  category() — char -> columna de la matriz

    private static int category(char c) {
        if (c >= 'A' && c <= 'Z') return C_UPPER;
        if (c >= 'a' && c <= 'z') return C_LOWER;
        if (c >= '0' && c <= '9') return C_DIGIT;
        switch (c) {
            case '{':  return C_LBRACE;
            case '}':  return C_RBRACE;
            case '=':  return C_ASSIGN;
            case '<':  return C_LT;
            case '>':  return C_GT;
            case '(':  return C_LPAREN;
            case ')':  return C_RPAREN;
            case '.':  return C_DOT;
            case '[':  return C_LBRACK;
            case ']':  return C_RBRACK;
            case '!':  return C_BANG;
            case ';':  return C_SEMI;
            case '"':  return C_QUOTE;
            case '+':  return C_PLUS;
            case '-':  return C_MINUS;
            case '*':  return C_STAR;
            case '/':  return C_SLASH;
            case '&':  return C_AMP;
            case '|':  return C_PIPE;
            case ',':  return C_COMMA;
            case ' ':  case '\t': case '\r': case '\f': case '\uFEFF': return C_SPACE;
            case '\n': return C_NEWLN;
            default:   return C_OTHER;
        }
    }

    private void avanzar(char c) {
        if (c == '\n') { line++; col = 1; } else { col++; }
        pos++;
    }

    private static String construirSugerencia(char c) {
        switch (c) {
            case '!':  return "'!' solo no es valido. Para desigualdad usa '!='.";
            case '&':  return "'&' solo no es valido. Para AND logico usa '&&'.";
            case '|':  return "'|' solo no es valido. Para OR logico usa '||'.";
            case '@':  return "'@' no pertenece al alfabeto del lenguaje.";
            case '#':  return "'#' no es valido. Los comentarios usan '//'.";
            case '$':  return "'$' no esta permitido.";
            case '`':  return "Usa '\"' para strings, no comilla invertida.";
            case '\'': return "Usa '\"' para strings. Comillas simples no soportadas.";
            default:
                return "Caracter '" + c + "' (ASCII " + (int)c + ") no pertenece al alfabeto.";
        }
    }

    public List<Token>         getTokens() { return Collections.unmodifiableList(tokens); }
    public List<compiladorErrores> getErrors() { return Collections.unmodifiableList(errors); }
    public boolean             hasErrors() { return !errors.isEmpty(); }

    public List<Importacion> getImportaciones() {
        List<Importacion> importaciones = new ArrayList<>();

        int i = 0;
        while (i < tokens.size() && tokens.get(i).tipo == tipoToken.PALABRA_CLAVE
                && tokens.get(i).lexema.equals("import")) {
            Token inicio = tokens.get(i++);
            StringBuilder ruta = new StringBuilder();
            boolean esperaId = true, valida = true;
            while (i < tokens.size() && !tokens.get(i).lexema.equals(";")
                    && !tokens.get(i).lexema.equals("{") && tokens.get(i).tipo != tipoToken.EOF) {
                Token parte = tokens.get(i++);
                if (esperaId && parte.tipo == tipoToken.IDENTIFICADOR) {
                    ruta.append(parte.lexema);
                    esperaId = false;
                } else if (!esperaId && parte.lexema.equals(".")) {
                    ruta.append('.');
                    esperaId = true;
                } else valida = false;
            }
            boolean termina = i < tokens.size() && tokens.get(i).lexema.equals(";");
            if (valida && !esperaId && termina)
                importaciones.add(new Importacion(ruta.toString(), inicio.linea, inicio.columna));
            if (!termina) break;
            i++;
        }

        return Collections.unmodifiableList(importaciones);
    }

}
