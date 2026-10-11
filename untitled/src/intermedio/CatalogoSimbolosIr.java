import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Crear un catálogo por módulo y registrar en orden de declaración, antes de emitir usos. */
final class CatalogoSimbolosIr {
    private final Map<Semantico.Simbolo, SimboloIr> simbolos = new IdentityHashMap<>();
    SimboloIr registrar(Semantico.Simbolo fuente, String clase, OrigenIr origen) {
        Objects.requireNonNull(fuente);
        if (fuente.propietaria != null && !fuente.propietaria.simbolo.nombre.equals(clase))
            throw new IllegalArgumentException("Clase declaradora incorrecta");
        SimboloIr existente = simbolos.get(fuente);
        if (existente != null) {
            if (!existente.propietaria.equals(clase) || !existente.tipo.equals(TipoIr.desdeSemantico(fuente.tipo)))
                throw new IllegalArgumentException("Símbolo registrado con otro contrato");
            return existente;
        }
        SimboloIr.Rol rol;
        switch (fuente.rol) {
            case CAMPO: rol = SimboloIr.Rol.CAMPO; break;
            case VARIABLE: case PARAMETRO: case ITERADOR: rol = SimboloIr.Rol.LOCAL; break;
            default: throw new IllegalArgumentException("Símbolo sin almacenamiento: " + fuente.rol);
        }
        SimboloIr simbolo = new SimboloIr("s" + simbolos.size(), fuente.nombre, TipoIr.desdeSemantico(fuente.tipo), rol, clase, origen);
        simbolos.put(fuente, simbolo);
        return simbolo;
    }
    SimboloIr obtener(Semantico.Simbolo fuente) {
        SimboloIr simbolo = simbolos.get(fuente);
        if (simbolo == null) throw new IllegalArgumentException("Símbolo no registrado");
        return simbolo;
    }
}
