#ifndef ANALIZADOR_RT_RUNTIME_V1_H
#define ANALIZADOR_RT_RUNTIME_V1_H
#include <stddef.h>
#include <stdint.h>
#include "rt_console.h"

/* H4: todos los valores de lenguaje ocupan una ranura de 8 bytes.
 * Los punteros y double se trasladan por bits, mediante memcpy en C. */
struct RtString { uint64_t length, reserved; unsigned char bytes[]; };
typedef struct RtType RtType;
typedef struct RtClass RtClass;
typedef struct RtObject { const void *const *vtable; const RtClass *klass; uint64_t fields[]; } RtObject;
typedef struct RtList { uint64_t length; const RtType *element; uint64_t items[]; } RtList;
enum { RT_INT=1, RT_FLOAT=2, RT_BOOL=3, RT_STRING=4, RT_NULL=5, RT_OBJECT=6, RT_LIST=7 };
struct RtType { uint64_t kind; const RtType *element; const RtClass *klass; };
struct RtClass {
    const RtString *name;
    uint64_t size;
    const void *const *vtable;
    const RtClass *base;
    uint64_t field_count;
    const RtType *const *field_types;
};
/* file: nombre UTF-8 válido, sin NUL interno, prestado e inmutable. */
typedef struct RtOrigin { const char *file; int64_t line, column; } RtOrigin;
enum { RT_NULL_REFERENCE=9, RT_BAD_INDEX=10, RT_ZERO_DIVISION=11 };
extern const RtType rt_type_int, rt_type_float, rt_type_bool, rt_type_string, rt_type_null;
extern const RtOrigin rt_origin_generated;

/* Reservas registradas hasta rt_memory_release_all. No hay GC ni free de handles. */
RtConsoleStatus rt_memory_try_alloc(uint64_t bytes, void **out);
void rt_memory_release_all(void);
RtConsoleStatus rt_string_try_new(const unsigned char *bytes, uint64_t length, RtString **out);
RtString *rt_string_new(const unsigned char *bytes, uint64_t length, const RtOrigin *origin);
RtObject *rt_object_new(const RtClass *klass, const RtOrigin *origin);
RtList *rt_list_new(int64_t length, const RtType *element, const RtOrigin *origin);
RtString *rt_value_to_string(uint64_t value, const RtType *type, const RtOrigin *origin);
RtString *rt_concat(const RtString *a, const RtString *b, const RtOrigin *origin);
uint64_t rt_string_equal(const RtString *a, const RtString *b);
void rt_check_null(const void *value, const RtOrigin *origin);
void rt_check_index(const RtList *list, int64_t index, const RtOrigin *origin);
int64_t rt_div_int(int64_t a, int64_t b, const RtOrigin *origin);
double rt_div_float(double a, double b, const RtOrigin *origin);
/* Conversión portada de OpenJDK 11, sin JVM ni printf de float. Buffer >=32. */
size_t rt_double_to_java11(double value, char out[32]);
/* Diagnóstico sin reservas nuevas; libera handles y termina con 1 aun si stderr falla. */
_Noreturn void rt_fail(uint32_t code, const RtOrigin *origin);
void rt_program_init(void);
void rt_program_shutdown(void);
typedef void (*RtMethodEntry)(RtObject *receiver);
int rt_run_program(const RtClass *entry_class, RtMethodEntry initializer, RtMethodEntry main_method);

/* ABI C de los adaptadores: valor de lenguaje directo en RAX/XMM0.
 * Los puentes por módulo capturan R10 y añaden descriptor/origen explícitos. */
void rt_lang_print(RtObject *receiver, RtString *text, uint64_t lf, const RtOrigin *origin);
int64_t rt_lang_read_int(RtObject *receiver, const RtOrigin *origin);
double rt_lang_read_float(RtObject *receiver, const RtOrigin *origin);
uint64_t rt_lang_read_bool(RtObject *receiver, const RtOrigin *origin);
RtString *rt_lang_read_line(RtObject *receiver, const RtOrigin *origin);

_Static_assert(sizeof(void*)==8 && sizeof(double)==8, "Linux x86-64 requerido");
_Static_assert(offsetof(RtString,bytes)==16 && sizeof(RtString)==16, "RtString ABI");
_Static_assert(offsetof(RtList,items)==16 && offsetof(RtList,element)==8, "RtList ABI");
_Static_assert(offsetof(RtObject,fields)==16 && offsetof(RtObject,klass)==8, "RtObject ABI");
_Static_assert(sizeof(RtConsoleError)==16 && offsetof(RtConsoleError,byte_offset)==8, "RC-1 ABI");
_Static_assert(sizeof(RtType)==24 && sizeof(RtClass)==48 && sizeof(RtOrigin)==24, "Descriptores ABI");
#endif
