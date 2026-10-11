/** ABI y formato encapsulados. El emisor del cuerpo solo usa registros volátiles comunes. */
final class AdaptadorPlataformaNasm {
    final DestinoNativoIr destino;
    AdaptadorPlataformaNasm(DestinoNativoIr destino) { this.destino=destino; }
    String prologo(String nombre, int bytes) {
        StringBuilder s=new StringBuilder("global ").append(nombre).append('\n').append(nombre).append(":\n    push rbp\n");
        s.append("abi_").append(nombre).append("_push:\n    mov rbp, rsp\n");
        if (bytes>0) {
            if (destino==DestinoNativoIr.WINDOWS_X64 && bytes>=4096)
                s.append("    mov rax, ").append(bytes).append("\n    call ___chkstk_ms\n    sub rsp, rax\n");
            else s.append("    sub rsp, ").append(bytes).append('\n');
        }
        s.append("abi_").append(nombre).append("_prologo:\n"); return s.toString();
    }
    String epilogo(int bytes) {
        // Forma reconocida por el desenrollador Windows; no usa LEAVE ni MOV RSP,RBP.
        return (bytes>0 ? "    add rsp, "+bytes+"\n" : "")+"    pop rbp\n    ret\n";
    }
    String fin(String nombre, int bytes) {
        StringBuilder s=new StringBuilder("abi_").append(nombre).append("_fin:\n");
        if (destino==DestinoNativoIr.WINDOWS_X64) {
            s.append("section .pdata rdata align=4\n    dd ").append(nombre).append(" wrt ..imagebase, abi_").append(nombre)
                    .append("_fin wrt ..imagebase, abi_").append(nombre).append("_unwind wrt ..imagebase\nsection .xdata rdata align=4\nalign 4\nabi_")
                    .append(nombre).append("_unwind:\n    db 1, abi_").append(nombre).append("_prologo-").append(nombre).append(", ");
            int slots=bytes==0?1:bytes<=128?2:bytes<=524280?3:4;
            s.append(slots).append(", 0\n");
            // UNWIND_INFO v1, sin frame register: RSP permanece fijo durante el cuerpo.
            if (bytes>0) {
                int op=bytes<=128 ? (((bytes-8)/8)<<4)|2 : bytes<=524280?1:17;
                s.append("    db abi_").append(nombre).append("_prologo-").append(nombre).append(", ").append(op).append('\n');
                if (bytes>128) s.append(bytes<=524280?"    dw "+bytes/8:"    dd "+bytes).append('\n');
            }
            s.append("    db abi_").append(nombre).append("_push-").append(nombre).append(", 80\n"); // UWOP_PUSH_NONVOL RBP
            if ((slots&1)!=0) s.append("    dw 0\n");
            s.append("section .text\n");
        }
        return s.toString();
    }
    String llamadaExterna(String simbolo) { return simbolo+(destino==DestinoNativoIr.LINUX_X64?" wrt ..plt":""); }
    String pilaNoEjecutable() { return destino==DestinoNativoIr.LINUX_X64 ? "section .note.GNU-stack noalloc noexec nowrite progbits\n" : ""; }
    String entrada(ModuloAtomicoIr m) {
        int bytes=destino==DestinoNativoIr.WINDOWS_X64?32:0;
        StringBuilder s=new StringBuilder("\n; ADAPTADOR: entrada CRT y ciclo del runtime\nextern rt_run_program\n");
        s.append(prologo("main",bytes));
        if (destino==DestinoNativoIr.LINUX_X64) s.append("    mov rdi, [rel rt_entry_class]\n    mov rsi, [rel rt_entry_init]\n    mov rdx, [rel rt_entry_main]\n");
        else s.append("    mov rcx, [rel rt_entry_class]\n    mov rdx, [rel rt_entry_init]\n    mov r8, [rel rt_entry_main]\n");
        s.append("    call ").append(llamadaExterna("rt_run_program")).append('\n').append(epilogo(bytes)).append(fin("main",bytes));
        return s.toString();
    }
    String puenteWindows(LlamadaAtomica l, DatosNativosIr d) {
        StringBuilder s=new StringBuilder(prologo(l.simbolo,32));
        if (l.consola!=null) {
            if (l.consola.imprime()) s.append("    mov r9, r10\n    mov r8d, ").append(l.consola==RuntimeConsolaId.CONSOLA_IMPRIMIR_LINEA?1:0).append('\n');
            else s.append("    mov rdx, r10\n");
        } else switch(l.helper) {
            case COMPROBAR_NULO: s.append("    mov rdx, r10\n"); break;
            case DIVIDIR_FLOAT: case COMPROBAR_INDICE: case DIVIDIR_INT: case CONCATENAR: s.append("    mov r8, r10\n"); break;
            case A_STRING:
                if (l.parametros.get(0).equals(TipoIr.FLOAT)) s.append("    movq rcx, xmm0\n");
                s.append("    lea rdx, [rel ").append(d.tipos.get(l.parametros.get(0))).append("]\n    mov r8, r10\n"); break;
            case NUEVO_OBJETO: s.append("    lea rcx, [rel ").append(DatosNativosIr.clase(l.retorno.clase)).append("]\n    mov rdx, r10\n"); break;
            case NUEVA_LISTA: s.append("    lea rdx, [rel ").append(d.tipos.get(l.retorno.elemento)).append("]\n    mov r8, r10\n"); break;
            case IGUAL_STRING: break;
            default: throw new AssertionError(l.helper);
        }
        return s.append("    call ").append(SoporteNativoIr.destino(l)).append('\n').append(epilogo(32)).append(fin(l.simbolo,32)).toString();
    }
}
