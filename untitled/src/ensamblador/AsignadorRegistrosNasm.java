import java.util.*;

/** Intervalos locales: R8/R9 son volátiles en ambos perfiles y no son scratch del emisor. */
final class AsignadorRegistrosNasm {
    private static final class Intervalo {
        final String id; final int inicio, fin;
        Intervalo(String id,int inicio,int fin) { this.id=id;this.inicio=inicio;this.fin=fin; }
    }
    Map<String,String> asignar(FuncionAtomicaIr funcion) {
        Map<String,Integer> definiciones=new HashMap<>(), segmentos=new HashMap<>(), ultimoUso=new HashMap<>();
        Set<String> externos=new HashSet<>(); List<List<Intervalo>> grupos=new ArrayList<>();grupos.add(new ArrayList<>());
        int segmento=0;
        for(int p=0;p<funcion.codigo.size();p++) {
            CuadruploIr q=funcion.codigo.get(p);boolean corte=corte(q.operacion);
            for(ValorIr v:usos(q))if(v.clase==ValorIr.Clase.TEMPORAL) {
                ultimoUso.put(v.id,p);
                if(corte || !Objects.equals(segmentos.get(v.id),segmento))externos.add(v.id);
            }
            if(!corte && q.destino!=null && q.destino.clase==OperandoAtomico.Clase.VALOR && q.destino.valor.clase==ValorIr.Clase.TEMPORAL) {
                String id=q.destino.valor.id; definiciones.put(id,p);segmentos.put(id,segmento);
            }
            if(corte) { segmento++;grupos.add(new ArrayList<>()); }
        }
        for(Map.Entry<String,Integer> d:definiciones.entrySet()) if(!externos.contains(d.getKey()))
            grupos.get(segmentos.get(d.getKey())).add(new Intervalo(d.getKey(),d.getValue(),ultimoUso.getOrDefault(d.getKey(),d.getValue())));
        Map<String,String> asignados=new LinkedHashMap<>();
        for(List<Intervalo> grupo:grupos) {
            grupo.sort(Comparator.comparingInt(i -> i.inicio));
            int[] ocupadoHasta={-1,-1};String[] registros={"r8","r9"};
            for(Intervalo i:grupo) for(int r=0;r<registros.length;r++)if(ocupadoHasta[r]<=i.inicio) {
                // Los operandos se cargan en scratch antes de guardar el resultado.
                asignados.put(i.id,registros[r]);ocupadoHasta[r]=i.fin;break;
            }
        }
        return Collections.unmodifiableMap(asignados);
    }
    private static boolean corte(CuadruploIr.Operacion o) {
        switch(o) {
            case LABEL:case JUMP:case JUMP_IF_FALSE:case READ_PARAM:case PARAM:case CALL:
            case CALL_INDIRECT:case LLAMAR_RUNTIME:case SET_RETURN:case RETURN:return true;
            default:return false;
        }
    }
    private static List<ValorIr> usos(CuadruploIr q) {
        List<ValorIr> usos=new ArrayList<>();for(OperandoAtomico a:Arrays.asList(q.fuente1,q.fuente2))if(a!=null) {
            if(a.clase==OperandoAtomico.Clase.VALOR)usos.add(a.valor);
            if(a.clase==OperandoAtomico.Clase.LLAMADA && a.llamada.puntero!=null)usos.add(a.llamada.puntero);
        }return usos;
    }
}
