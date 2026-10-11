#ifndef ANALIZADOR_RT_CONSOLE_V1_H
#define ANALIZADOR_RT_CONSOLE_V1_H

/* RC-1: ABI implementada en runtime/nativo/src/rt_console.c. Linux x86-64, System V. */
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RT_CONSOLE_ABI_VERSION UINT32_C(1)
#define RT_CONSOLE_MAX_LINE_BYTES UINT64_C(1048576)

/* Handle opaco de 16 bytes de cabecera + contenido UTF-8; layout en rt_runtime.h. */
typedef struct RtString RtString;
typedef uint32_t RtConsoleStatus;

enum {
    RT_CONSOLE_OK = 0,
    RT_CONSOLE_EOF = 1,
    RT_CONSOLE_INVALID_INPUT = 2,
    RT_CONSOLE_OUT_OF_RANGE = 3,
    RT_CONSOLE_INVALID_UTF8 = 4,
    RT_CONSOLE_LINE_TOO_LONG = 5,
    RT_CONSOLE_READ_ERROR = 6,
    RT_CONSOLE_WRITE_ERROR = 7,
    RT_CONSOLE_OUT_OF_MEMORY = 8
};

/* ABI x86-64: offsets 0/4/8 y tamaño 16. Origen del programa en el caller.
 * code coincide con el retorno. system_errno=0 si no hay causa del sistema.
 * byte_offset es 1-based en la línea sin terminador, antes de recortar espacios;
 * 0 si no aplica. UTF-8: primer byte inválido (o inicio de secuencia truncada);
 * exceso de longitud: límite+1. Formato, rango, EOF, I/O y memoria usan 0.
 * En OK los tres campos valen 0; en EOF code=EOF, resto=0.
 */
typedef struct RtConsoleError {
    uint32_t code;
    int32_t system_errno;
    uint64_t byte_offset;
} RtConsoleError;

uint32_t rt_console_v1_abi_version(void);
/* Init instala SIG_IGN para SIGPIPE antes de cualquier salida (sigaction con
 * retorno comprobado). Fallo de preparación de salida -> WRITE_ERROR.
 * Mantener la disposición durante flush y diagnóstico; EPIPE -> WRITE_ERROR.
 */
RtConsoleStatus rt_console_v1_init(RtConsoleError *error);
RtConsoleStatus rt_console_v1_shutdown(RtConsoleError *error);

/* texto, error y punteros out deben ser válidos/no nulos.
 * agregar_lf es exactamente 0 o 1; write siempre vacía stdout.
 * El texto se toma prestado: no se modifica ni libera. Un null del lenguaje
 * se convierte primero a un RtString con el texto "null".
 */
RtConsoleStatus rt_console_v1_write(const RtString *texto,
                                  uint32_t agregar_lf,
                                  RtConsoleError *error);

/* read_line: OK -> *out_value string gestionado; EOF -> *out_value=NULL.
 * Otros fallos dejan *out_value intacto. El caller nunca libera el handle.
 * Lecturas escalares: *out_value solo se modifica en OK; EOF es fallo del
 * lenguaje. bool devuelve 0/1 en uint32_t, sin fijar el layout bool de H4.
 */
RtConsoleStatus rt_console_v1_read_line(RtString **out_value,
                                      RtConsoleError *error);
RtConsoleStatus rt_console_v1_read_int(int64_t *out_value,
                                     RtConsoleError *error);
RtConsoleStatus rt_console_v1_read_float(double *out_value,
                                       RtConsoleError *error);
RtConsoleStatus rt_console_v1_read_bool(uint32_t *out_value,
                                      RtConsoleError *error);

#ifdef __cplusplus
}
#endif
#endif
