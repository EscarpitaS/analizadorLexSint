import java.util.*;

/** H5: núcleo x86-64 compartido. Consume solo cuádruplos y offsets verificados. */
final class GeneradorEnsamblador {
    private final boolean optimizar;
    GeneradorEnsamblador() { this(false); }
    GeneradorEnsamblador(boolean optimizar) { this.optimizar=optimizar; }
    String generar(ModuloAtomicoIr m) {
        DatosNativosIr datos=new DatosNativosIr(m);
        if (m.entrada==null) throw new IllegalArgumentException("El ejecutable requiere una entrada seleccionada");
        validarSimbolos(m,datos);
        AdaptadorPlataformaNasm plataforma=new AdaptadorPlataformaNasm(m.layout.destino);
        String soporte=new SoporteNativoIr().nasm(m);
        for (FuncionAtomicaIr f:m.funciones) soporte=soporte.replace("extern "+LayoutProgramaIr.nombreFuncion(f.firma)+"\n", "");
        StringBuilder s=new StringBuilder("; H5 destino="+m.layout.destino.id+" ABI="+(m.layout.destino==DestinoNativoIr.LINUX_X64?"System V AMD64":"Microsoft x64")+"\n");
        s.append("; SOPORTE: constantes, descriptores y puentes al runtime\n").append(soporte).append("\nsection .text\n");
        if (m.layout.destino==DestinoNativoIr.WINDOWS_X64 && m.layout.marcos.values().stream().anyMatch(f->f.tamano>=4096)) s.append("extern ___chkstk_ms\n");
        for(FuncionAtomicaIr f:m.funciones) {
            LayoutProgramaIr.Marco marco=m.layout.marcos.get(f.firma.id()); String nombre=LayoutProgramaIr.nombreFuncion(f.firma);
            s.append("\n; ADAPTADOR: marco de ").append(f.firma.id()).append('\n').append(plataforma.prologo(nombre,marco.tamano));
            // Locales sin inicializador tienen el valor predeterminado de H0.
            for(int offset:marco.locales.values()) s.append("    mov qword [rbp").append(offset).append("], 0\n");
            Map<String,String> registros=optimizar?new AsignadorRegistrosNasm().asignar(f):Collections.emptyMap();
            if(optimizar)s.append("; H7 temporales en registros volátiles: ").append(registros.size()).append('\n');
            Emisor cuerpo=new Emisor(s,datos,marco,plataforma,registros);
            int indice=0;
            for(CuadruploIr q:f.codigo) {
                s.append("; IR #").append(indice++).append(' ').append(q.operacion).append(" @ :").append(q.origen.linea).append(':').append(q.origen.columna).append('\n');
                boolean saltoRedundante=optimizar && q.operacion==CuadruploIr.Operacion.JUMP
                        && indice<f.codigo.size() && f.codigo.get(indice).operacion==CuadruploIr.Operacion.LABEL
                        && q.fuente1.etiqueta.equals(f.codigo.get(indice).destino.etiqueta);
                if(!saltoRedundante)cuerpo.instruccion(q);
            }
            s.append(plataforma.fin(nombre,marco.tamano));
        }
        return s.append(plataforma.entrada(m)).append(plataforma.pilaNoEjecutable()).toString();
    }
    private void validarSimbolos(ModuloAtomicoIr m,DatosNativosIr d) {
        Set<String> reservados=new HashSet<>(Arrays.asList("main","rt_entry_class","rt_entry_init","rt_entry_main"));
        reservados.addAll(d.strings.values()); reservados.addAll(d.tipos.values());
        for(String o:d.origenes.keySet()) { reservados.add(o); reservados.add(o+"_archivo"); }
        for(String c:d.clases.keySet()) { reservados.add(DatosNativosIr.clase(c)); reservados.add(DatosNativosIr.clase(c)+"_campos"); reservados.add(DatosNativosIr.vtable(c)); }
        for(FuncionAtomicaIr f:m.funciones) for(CuadruploIr q:f.codigo) {
            if(q.operacion==CuadruploIr.Operacion.LLAMAR_RUNTIME) reservados.add(q.fuente1.llamada.simbolo);
        }
        for(FuncionAtomicaIr f:m.funciones) for(CuadruploIr q:f.codigo)
            if(q.operacion==CuadruploIr.Operacion.LABEL && (reservados.contains(q.destino.etiqueta) || q.destino.etiqueta.startsWith("abi_")))
                throw new IllegalArgumentException("Etiqueta colisiona con soporte nativo: "+q.destino.etiqueta);
    }
    private static final class Emisor {
        final StringBuilder s; final DatosNativosIr datos; final LayoutProgramaIr.Marco marco; final AdaptadorPlataformaNasm plataforma; final Map<String,String> registros;
        Emisor(StringBuilder s,DatosNativosIr datos,LayoutProgramaIr.Marco marco,AdaptadorPlataformaNasm plataforma,Map<String,String> registros) { this.registros=registros;this.s=s;this.datos=datos;this.marco=marco;this.plataforma=plataforma; }
        void linea(String x) { s.append("    ").append(x).append('\n'); }
        String ranura(ValorIr v) { return "[rbp"+marco.offset(v)+"]"; }
        void cargar(ValorIr v,String registro) {
            String asignado=registros.get(v.id);
            if(asignado!=null) { linea((registro.startsWith("xmm")?"movq ":"mov ")+registro+", "+asignado); return; }
            if(registro.startsWith("xmm")) {
                if(v.clase==ValorIr.Clase.CONSTANTE) { cargar(v,"rax"); linea("movq "+registro+", rax"); }
                else linea("movsd "+registro+", "+ranura(v));
            } else if(v.clase!=ValorIr.Clase.CONSTANTE) linea("mov "+registro+", "+ranura(v));
            else if(v.tipo.equals(TipoIr.STRING)) linea("lea "+registro+", [rel "+datos.strings.get((String)v.literal)+"]");
            else {
                long bits=v.tipo.equals(TipoIr.FLOAT)?Double.doubleToRawLongBits((Double)v.literal):v.tipo.equals(TipoIr.BOOL)?((Boolean)v.literal?1:0):v.tipo.equals(TipoIr.NULO)?0:(Long)v.literal;
                linea("mov "+registro+", 0x"+Long.toUnsignedString(bits,16));
            }
        }
        void guardar(ValorIr v,String registro) {
            String asignado=registros.get(v.id);
            if(asignado!=null)linea((registro.startsWith("xmm")?"movq ":"mov ")+asignado+", "+registro);
            else linea((registro.startsWith("xmm")?"movsd ":"mov ")+ranura(v)+", "+registro);
        }
        void instruccion(CuadruploIr q) {
            ValorIr a=q.fuente1!=null && q.fuente1.clase==OperandoAtomico.Clase.VALOR?q.fuente1.valor:null;
            ValorIr b=q.fuente2!=null && q.fuente2.clase==OperandoAtomico.Clase.VALOR?q.fuente2.valor:null;
            ValorIr d=q.destino!=null && q.destino.clase==OperandoAtomico.Clase.VALOR?q.destino.valor:null;
            switch(q.operacion) {
                case LABEL: s.append('\n').append(q.destino.etiqueta).append(":\n"); return;
                case JUMP: linea("jmp "+q.fuente1.etiqueta); return;
                case JUMP_IF_FALSE: cargar(a,"rax"); linea("test rax, rax"); linea("jz "+q.fuente2.etiqueta); return;
                case READ_PARAM: {
                    LugarArgumentoIr p=q.fuente1.argumento;
                    if(p.registro!=null) guardar(d,p.registro);
                    else { linea("mov rax, [rbp+"+p.offset+"]"); guardar(d,"rax"); }
                    return;
                }
                case PARAM: {
                    LugarArgumentoIr p=q.fuente2.argumento;
                    if(p.registro!=null) cargar(a,p.registro);
                    else { cargar(a,"rax"); linea("mov [rsp+"+p.offset+"], rax"); }
                    return;
                }
                case CALL: linea("call "+q.fuente1.llamada.simbolo); return;
                case CALL_INDIRECT: cargar(q.fuente1.llamada.puntero,"r11"); linea("call r11"); return;
                case LLAMAR_RUNTIME: linea("lea r10, [rel "+datos.origen(q.origen)+"]"); linea("call "+q.fuente1.llamada.simbolo); return;
                case GET_RETURN: guardar(d,ConvencionLlamadasIr.retorno(d.tipo)); return;
                case SET_RETURN: cargar(a,ConvencionLlamadasIr.retorno(a.tipo)); return;
                case RETURN: s.append(plataforma.epilogo(marco.tamano)); return;
                case LEA_LOCAL: linea("lea rax, [rbp"+(Long)a.literal+"]"); guardar(d,"rax"); return;
                case LEA: cargar(a,"rax"); cargar(b,"rdx"); linea("add rax, rdx"); guardar(d,"rax"); return;
                case LOAD: cargar(a,"rax"); cargar(b,"rdx"); linea("mov rax, [rax+rdx]"); guardar(d,"rax"); return;
                case STORE: cargar(a,"rax"); cargar(b,"rdx"); linea("mov [rax], rdx"); return;
                case COPY: cargar(a,"rax"); guardar(d,"rax"); return;
                case INT_A_FLOAT: cargar(a,"rax"); linea("cvtsi2sd xmm4, rax"); guardar(d,"xmm4"); return;
                case NEG:
                    cargar(a,"rax");
                    if(a.tipo.equals(TipoIr.FLOAT)) { linea("mov rdx, 0x8000000000000000"); linea("xor rax, rdx"); }
                    else linea("neg rax");
                    guardar(d,"rax"); return;
                case NOT: cargar(a,"rax"); linea("test rax, rax"); linea("sete al"); linea("movzx rax, al"); guardar(d,"rax"); return;
                case ADD: case SUB: case MUL: {
                    boolean flotante=a.tipo.equals(TipoIr.FLOAT); String x=flotante?"xmm4":"rax",y=flotante?"xmm5":"rdx";
                    cargar(a,x);cargar(b,y);
                    String op=q.operacion==CuadruploIr.Operacion.ADD?"add":q.operacion==CuadruploIr.Operacion.SUB?"sub":flotante?"mul":"imul";
                    linea(op+(flotante?"sd":"")+" "+x+", "+y); guardar(d,x); return;
                }
                case EQ: case NE: case LT: case LE: case GT: case GE: {
                    boolean flotante=a.tipo.equals(TipoIr.FLOAT); cargar(a,flotante?"xmm4":"rax"); cargar(b,flotante?"xmm5":"rdx");
                    linea(flotante?"ucomisd xmm4, xmm5":"cmp rax, rdx"); String cc;
                    switch(q.operacion) { case EQ:cc="e";break;case NE:cc="ne";break;case LT:cc=flotante?"b":"l";break;case LE:cc=flotante?"be":"le";break;case GT:cc=flotante?"a":"g";break;default:cc=flotante?"ae":"ge"; }
                    linea("set"+cc+" al");
                    if(flotante) {
                        if(q.operacion==CuadruploIr.Operacion.NE) { linea("setp dl");linea("or al, dl"); }
                        else if(q.operacion==CuadruploIr.Operacion.EQ || q.operacion==CuadruploIr.Operacion.LT || q.operacion==CuadruploIr.Operacion.LE) { linea("setnp dl");linea("and al, dl"); }
                    }
                    linea("movzx rax, al"); guardar(d,"rax"); return;
                }
                default: throw new IllegalArgumentException("Operación sin emisión NASM: "+q.operacion);
            }
        }
    }
}
