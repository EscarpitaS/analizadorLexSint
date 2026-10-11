import java.util.*;

/** H7: transformación inmutable; solo descarta operaciones demostrablemente sin efectos ni errores. */
final strictfp class OptimizadorIr {
    static final class Resultado {
        final ModuloIr original, optimizado;
        final int instruccionesAntes, instruccionesDespues, bloquesAntes, bloquesDespues;
        Resultado(ModuloIr original, ModuloIr optimizado) {
            this.original=original; this.optimizado=optimizado;
            instruccionesAntes=instrucciones(original); instruccionesDespues=instrucciones(optimizado);
            bloquesAntes=bloques(original); bloquesDespues=bloques(optimizado);
        }
        String resumen() { return "Instrucciones: " + instruccionesAntes + " → " + instruccionesDespues
                + " · Bloques: " + bloquesAntes + " → " + bloquesDespues; }
        private static int instrucciones(ModuloIr m) { int n=0; for(FuncionIr f:m.funciones) for(BloqueIr b:f.bloques)n+=b.instrucciones.size(); return n; }
        private static int bloques(ModuloIr m) { int n=0; for(FuncionIr f:m.funciones)n+=f.bloques.size(); return n; }
    }
    Resultado optimizar(ModuloIr original) {
        validar(original);
        List<FuncionIr> funciones=new ArrayList<>();
        for(FuncionIr f:original.funciones) { comprobarCancelacion();funciones.add(optimizar(f)); }
        ModuloIr resultado=new ModuloIr(original.nombre,original.clases,funciones,original.entrada);
        validar(resultado); return new Resultado(original,resultado);
    }
    private static void comprobarCancelacion() { if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Optimización cancelada"); }
    private static void validar(ModuloIr m) {
        comprobarCancelacion();
        ResultadoVerificacionIr r=new VerificadorIr().verificar(m);
        if(!r.esExitoso())throw new IllegalArgumentException("IR inválida para H7: "+r.getErrores());
    }
    private FuncionIr optimizar(FuncionIr original) {
        FuncionIr f=original; String previo;
        do {
            comprobarCancelacion(); previo=firmaTexto(f);
            f=propagar(f); f=flujo(f); f=eliminarMuertas(f);
        } while(!previo.equals(firmaTexto(f)));
        return f;
    }
    private static String firmaTexto(FuncionIr f) {
        StringBuilder s=new StringBuilder();
        for(BloqueIr b:f.bloques) {
            s.append(b.id).append(':');
            for(InstruccionIr i:b.instrucciones) {
                s.append(i.operacion).append('/').append(i.resultado==null?"-":i.resultado.id).append('/');
                for(ValorIr v:i.operandos)valorTexto(s,v);
                s.append(';');
            }
            s.append(b.terminador.clase).append(b.terminador.destinos);valorTexto(s,b.terminador.valor);
        }
        return s.toString();
    }
    private static void valorTexto(StringBuilder s,ValorIr v) {
        if(v==null) { s.append("null;");return; }
        s.append(v.clase).append(':').append(v.tipo).append(':');
        if(v.clase!=ValorIr.Clase.CONSTANTE)s.append(v.id);
        else if(v.literal instanceof Double)s.append(Long.toHexString(Double.doubleToRawLongBits((Double)v.literal)));
        else if(v.literal instanceof String)s.append(ImpresorIr.escapar((String)v.literal));
        else s.append(v.literal);
        s.append(';');
    }
    private FuncionIr propagar(FuncionIr f) {
        Map<String,ValorIr> sustituciones=new HashMap<>(); List<BloqueIr> bloques=new ArrayList<>();
        // Obtener copias/constantes transitivas independientemente del orden textual de bloques.
        boolean cambio;
        do {
            cambio=false;
            for(BloqueIr b:f.bloques) for(InstruccionIr i:b.instrucciones) {
                comprobarCancelacion();
                if(i.resultado==null)continue;
                InstruccionIr nueva=reescribir(i,sustituciones);
                ValorIr v=plegar(nueva);
                if(v!=null && v.tipo.equals(i.resultado.tipo) && !sustituciones.containsKey(i.resultado.id)) {
                    sustituciones.put(i.resultado.id,v); cambio=true;
                }
            }
        } while(cambio);
        for(BloqueIr b:f.bloques) {
            Map<String,String> direcciones=new HashMap<>(); Map<String,ValorIr> locales=new HashMap<>();
            List<InstruccionIr> instrucciones=new ArrayList<>();
            for(InstruccionIr i:b.instrucciones) {
                InstruccionIr n=reescribir(i,sustituciones);
                if(n.operacion==InstruccionIr.Operacion.DIRECCION_LOCAL) direcciones.put(n.resultado.id,n.simbolo.id);
                if(n.operacion==InstruccionIr.Operacion.CARGAR) {
                    String local=local(n.operandos.get(0),direcciones);
                    ValorIr valor=local==null?null:locales.get(local);
                    if(valor!=null && valor.clase==ValorIr.Clase.CONSTANTE) {
                        n=InstruccionIr.operacion(InstruccionIr.Operacion.COPIAR,n.resultado,n.origen,valor);
                        sustituciones.put(n.resultado.id,valor);
                    }
                }
                ValorIr valor=plegar(n);
                if(valor!=null && n.resultado!=null && valor.tipo.equals(n.resultado.tipo)) {
                    sustituciones.put(n.resultado.id,valor);
                    n=InstruccionIr.operacion(InstruccionIr.Operacion.COPIAR,n.resultado,n.origen,valor);
                }
                if(n.operacion==InstruccionIr.Operacion.GUARDAR) {
                    String local=local(n.operandos.get(0),direcciones);
                    if(local==null) locales.clear(); else locales.put(local,n.operandos.get(1));
                } else if(n.operacion==InstruccionIr.Operacion.LLAMAR_DIRECTA || n.operacion==InstruccionIr.Operacion.LLAMAR_VIRTUAL
                        || n.operacion==InstruccionIr.Operacion.LLAMAR_RUNTIME) locales.clear();
                instrucciones.add(n);
            }
            bloques.add(new BloqueIr(b.id,instrucciones,terminador(b.terminador,sustituciones)));
        }
        // Aplicar también las sustituciones descubiertas en bloques posteriores textualmente.
        List<BloqueIr> finales=new ArrayList<>();
        for(BloqueIr b:bloques) {
            List<InstruccionIr> is=new ArrayList<>();for(InstruccionIr i:b.instrucciones)is.add(reescribir(i,sustituciones));
            finales.add(new BloqueIr(b.id,is,terminador(b.terminador,sustituciones)));
        }
        return funcion(f,finales);
    }
    private static String local(ValorIr v,Map<String,String> direcciones) { return v.clase==ValorIr.Clase.CONSTANTE?null:direcciones.get(v.id); }
    private static ValorIr resolver(ValorIr v,Map<String,ValorIr> mapa) {
        if(v==null)return null; Set<String> vistos=new HashSet<>();
        while(v.clase!=ValorIr.Clase.CONSTANTE && mapa.containsKey(v.id) && vistos.add(v.id))v=mapa.get(v.id);
        return v;
    }
    private static InstruccionIr reescribir(InstruccionIr i,Map<String,ValorIr> mapa) {
        ValorIr[] a=new ValorIr[i.operandos.size()];for(int n=0;n<a.length;n++)a[n]=resolver(i.operandos.get(n),mapa);
        switch(i.operacion) {
            case DIRECCION_LOCAL:return i;
            case DIRECCION_CAMPO:return InstruccionIr.direccionCampo(i.resultado,i.simbolo,a[0],i.origen);
            case CONVERTIR:return InstruccionIr.convertir(i.resultado,i.conversion,a[0],i.origen);
            case LLAMAR_RUNTIME:return InstruccionIr.runtime(i.runtime,i.resultado,i.origen,a);
            case LLAMAR_DIRECTA:case LLAMAR_VIRTUAL:return InstruccionIr.llamar(i.operacion==InstruccionIr.Operacion.LLAMAR_VIRTUAL,
                    i.resultado,i.firma,a[0],i.origen,Arrays.copyOfRange(a,1,a.length));
            default:return InstruccionIr.operacion(i.operacion,i.resultado,i.origen,a);
        }
    }
    private static TerminadorIr terminador(TerminadorIr t,Map<String,ValorIr> mapa) {
        ValorIr v=resolver(t.valor,mapa);
        switch(t.clase) {
            case SALTO:return t;
            case RETORNO:return TerminadorIr.retorno(v,t.origen);
            default:
                if(v.clase==ValorIr.Clase.CONSTANTE)return TerminadorIr.salto(t.destinos.get((Boolean)v.literal?0:1),t.origen);
                if(t.destinos.get(0).equals(t.destinos.get(1)))return TerminadorIr.salto(t.destinos.get(0),t.origen);
                return TerminadorIr.condicional(v,t.destinos.get(0),t.destinos.get(1),t.origen);
        }
    }
    private static ValorIr plegar(InstruccionIr i) {
        if(i.operacion==InstruccionIr.Operacion.COPIAR)return i.operandos.get(0);
        for(ValorIr v:i.operandos)if(v.clase!=ValorIr.Clase.CONSTANTE)return null;
        if(i.operandos.isEmpty())return null;
        ValorIr a=i.operandos.get(0), b=i.operandos.size()>1?i.operandos.get(1):null;
        Object x=a.literal,y=b==null?null:b.literal;
        switch(i.operacion) {
            case CONVERTIR:return i.conversion==InstruccionIr.Conversion.INT_A_FLOAT?ValorIr.flotante(((Long)x).doubleValue()):null;
            case NO:return ValorIr.booleano(!(Boolean)x);
            case NEGAR:return a.tipo.equals(TipoIr.INT)?ValorIr.entero(-((Long)x)):ValorIr.flotante(-((Double)x));
            case SUMAR:case RESTAR:case MULTIPLICAR:case DIVIDIR:
                if(a.tipo.equals(TipoIr.INT)) {
                    long p=(Long)x,q=(Long)y;
                    switch(i.operacion) {
                        case SUMAR:return ValorIr.entero(p+q);case RESTAR:return ValorIr.entero(p-q);case MULTIPLICAR:return ValorIr.entero(p*q);
                        default:return q==0?null:ValorIr.entero(ContratoEjecucion.dividirEnteros(p,q));
                    }
                }
                double p=(Double)x,q=(Double)y;
                switch(i.operacion) {
                    case SUMAR:return ValorIr.flotante(p+q);case RESTAR:return ValorIr.flotante(p-q);case MULTIPLICAR:return ValorIr.flotante(p*q);
                    default:return q==0.0?null:ValorIr.flotante(p/q);
                }
            case IGUAL:case DISTINTO:
                // Comparaciones de strings/referencias conservadas: no modificar identidad ni helpers.
                if(!a.tipo.equals(TipoIr.INT) && !a.tipo.equals(TipoIr.FLOAT) && !a.tipo.equals(TipoIr.BOOL))return null;
                boolean igual=a.tipo.equals(TipoIr.FLOAT)?((Double)x).doubleValue()==((Double)y).doubleValue():Objects.equals(x,y);
                return ValorIr.booleano(i.operacion==InstruccionIr.Operacion.IGUAL?igual:!igual);
            case MENOR:case MENOR_IGUAL:case MAYOR:case MAYOR_IGUAL:
                if(a.tipo.equals(TipoIr.INT)) {
                    long l=(Long)x,r=(Long)y;
                    return ValorIr.booleano(i.operacion==InstruccionIr.Operacion.MENOR?l<r:i.operacion==InstruccionIr.Operacion.MENOR_IGUAL?l<=r:i.operacion==InstruccionIr.Operacion.MAYOR?l>r:l>=r);
                }
                double l=(Double)x,r=(Double)y;
                return ValorIr.booleano(i.operacion==InstruccionIr.Operacion.MENOR?l<r:i.operacion==InstruccionIr.Operacion.MENOR_IGUAL?l<=r:i.operacion==InstruccionIr.Operacion.MAYOR?l>r:l>=r);
            default:return null;
        }
    }
    private FuncionIr flujo(FuncionIr f) {
        Map<String,BloqueIr> mapa=new LinkedHashMap<>();for(BloqueIr b:f.bloques)mapa.put(b.id,b);
        List<BloqueIr> nuevos=new ArrayList<>();
        for(BloqueIr b:f.bloques) {
            TerminadorIr t=b.terminador;
            if(t.clase==TerminadorIr.Clase.SALTO)t=TerminadorIr.salto(seguir(t.destinos.get(0),mapa),t.origen);
            else if(t.clase==TerminadorIr.Clase.CONDICIONAL)t=TerminadorIr.condicional(t.valor,seguir(t.destinos.get(0),mapa),seguir(t.destinos.get(1),mapa),t.origen);
            nuevos.add(new BloqueIr(b.id,b.instrucciones,t));
        }
        mapa.clear();for(BloqueIr b:nuevos)mapa.put(b.id,b);
        Set<String> alcanzables=new HashSet<>();Deque<String> cola=new ArrayDeque<>();cola.add(nuevos.get(0).id);
        while(!cola.isEmpty()) { String id=cola.remove();if(alcanzables.add(id))cola.addAll(mapa.get(id).terminador.destinos); }
        nuevos.removeIf(b -> !alcanzables.contains(b.id));
        // Fusionar aristas de un solo predecesor; no fusionar entrada ni ciclos propios.
        boolean fusion;
        do {
            fusion=false;Map<String,Integer> entradas=new HashMap<>();for(BloqueIr b:nuevos)for(String d:new HashSet<>(b.terminador.destinos))entradas.merge(d,1,Integer::sum);
            mapa.clear();for(BloqueIr b:nuevos)mapa.put(b.id,b);
            for(int j=0;j<nuevos.size();j++) {
                BloqueIr b=nuevos.get(j); if(b.terminador.clase!=TerminadorIr.Clase.SALTO)continue;
                String destino=b.terminador.destinos.get(0);
                if(destino.equals(b.id)||destino.equals(nuevos.get(0).id)||entradas.getOrDefault(destino,0)!=1)continue;
                BloqueIr d=mapa.get(destino);List<InstruccionIr> is=new ArrayList<>(b.instrucciones);is.addAll(d.instrucciones);
                nuevos.set(j,new BloqueIr(b.id,is,d.terminador));nuevos.remove(d);fusion=true;break;
            }
        } while(fusion);
        return funcion(f,nuevos);
    }
    private static String seguir(String inicial,Map<String,BloqueIr> bloques) {
        String d=inicial;Set<String> vistos=new HashSet<>();
        while(vistos.add(d)) { BloqueIr b=bloques.get(d);if(!b.instrucciones.isEmpty()||b.terminador.clase!=TerminadorIr.Clase.SALTO)return d; d=b.terminador.destinos.get(0); }
        return inicial; // Un ciclo de saltos sigue siendo un ciclo, nunca un retorno.
    }
    private FuncionIr eliminarMuertas(FuncionIr f) {
        Map<String,InstruccionIr> definiciones=new HashMap<>();Set<String> vivos=new HashSet<>();Deque<ValorIr> cola=new ArrayDeque<>();
        for(BloqueIr b:f.bloques) {
            if(b.terminador.valor!=null)cola.add(b.terminador.valor);
            for(InstruccionIr i:b.instrucciones) {
                if(i.resultado!=null)definiciones.put(i.resultado.id,i);
                if(!pura(i))cola.addAll(i.operandos);
            }
        }
        while(!cola.isEmpty()) { ValorIr v=cola.remove();if(v.clase!=ValorIr.Clase.TEMPORAL||!vivos.add(v.id))continue;InstruccionIr i=definiciones.get(v.id);if(i!=null)cola.addAll(i.operandos); }
        List<BloqueIr> bloques=new ArrayList<>();
        for(BloqueIr b:f.bloques) {
            List<InstruccionIr> is=new ArrayList<>();for(InstruccionIr i:b.instrucciones)if(!pura(i)||i.resultado!=null&&vivos.contains(i.resultado.id))is.add(i);
            bloques.add(new BloqueIr(b.id,is,b.terminador));
        }
        return funcion(f,bloques);
    }
    private static boolean pura(InstruccionIr i) {
        switch(i.operacion) {
            case COPIAR:case DIRECCION_LOCAL:case SUMAR:case RESTAR:case MULTIPLICAR:case NEGAR:case NO:
            case IGUAL:case DISTINTO:case MENOR:case MENOR_IGUAL:case MAYOR:case MAYOR_IGUAL:return true;
            case CONVERTIR:return i.conversion!=InstruccionIr.Conversion.A_STRING;
            // LOAD puede leer una local sin inicializar; direcciones de objetos/listas pueden fallar.
            // División, llamadas, asignaciones, checks y asignación de memoria siempre se conservan.
            default:return false;
        }
    }
    private static FuncionIr funcion(FuncionIr f,List<BloqueIr> bloques) { return new FuncionIr(f.firma,f.receptor,f.parametros,f.locales,bloques); }
}
