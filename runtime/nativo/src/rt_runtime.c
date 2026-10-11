#include "rt_runtime.h"
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rt_platform.h"

const RtType rt_type_int={RT_INT,NULL,NULL},rt_type_float={RT_FLOAT,NULL,NULL},rt_type_bool={RT_BOOL,NULL,NULL},rt_type_string={RT_STRING,NULL,NULL},rt_type_null={RT_NULL,NULL,NULL};
const RtOrigin rt_origin_generated={"<generado>",0,0};
static const char *error_name(uint32_t code) {
    switch(code) {
        case RT_CONSOLE_EOF: return "FIN_DE_ENTRADA";
        case RT_CONSOLE_INVALID_INPUT: return "ENTRADA_INVALIDA";
        case RT_CONSOLE_OUT_OF_RANGE: return "ENTRADA_FUERA_DE_RANGO";
        case RT_CONSOLE_INVALID_UTF8: return "UTF8_INVALIDO";
        case RT_CONSOLE_LINE_TOO_LONG: return "LINEA_DEMASIADO_LARGA";
        case RT_CONSOLE_READ_ERROR: return "ERROR_LECTURA_CONSOLA";
        case RT_CONSOLE_WRITE_ERROR: return "ERROR_ESCRITURA_CONSOLA";
        case RT_CONSOLE_OUT_OF_MEMORY: return "MEMORIA_AGOTADA";
        case RT_NULL_REFERENCE: return "REFERENCIA_NULA";
        case RT_BAD_INDEX: return "INDICE_FUERA_DE_RANGO";
        case RT_ZERO_DIVISION: return "DIVISION_POR_CERO";
        default: return "ERROR_RUNTIME";
    }
}
static void stderr_bytes(const char *p,size_t n) {
    while(n) {
        ptrdiff_t done=rt_platform_write(2,(const unsigned char *)p,n);
        if(done<0 && errno==EINTR) continue;
        if(done<=0) return;
        p+=done; n-=(size_t)done;
    }
}
static void stderr_text(const char *p) { stderr_bytes(p,strlen(p)); }
static void stderr_unicode(unsigned value) {
    static const char digits[]="0123456789abcdef";
    char hex[6]={'\\','u',digits[(value>>12)&15],digits[(value>>8)&15],digits[(value>>4)&15],digits[value&15]};
    stderr_bytes(hex,sizeof(hex));
}
static void stderr_path(const char *p) {
    stderr_text("\"");
    while(*p) {
        unsigned char c=(unsigned char)*p;
        if(c=='\\') { stderr_text("\\\\"); p++; }
        else if(c=='"') { stderr_text("\\\""); p++; }
        else if(c=='\n') { stderr_text("\\n"); p++; }
        else if(c=='\r') { stderr_text("\\r"); p++; }
        else if(c=='\t') { stderr_text("\\t"); p++; }
        else if(c<32 || c==127) { stderr_unicode(c); p++; }
        else if(c<128) { stderr_bytes(p,1); p++; }
        else {
            /* Los nombres suministrados por el compilador son UTF-8 válido.
             * El impresor Java escapa ISOControl y cada surrogate UTF-16. */
            size_t n=c<0xe0?2:c<0xf0?3:4; unsigned cp=c&(n==2?31:n==3?15:7);
            for(size_t i=1;i<n;i++) cp=(cp<<6)|((unsigned char)p[i]&63);
            if(cp>=128 && cp<=159) stderr_unicode(cp);
            else if(cp>65535) { cp-=65536; stderr_unicode(0xd800+(cp>>10)); stderr_unicode(0xdc00+(cp&1023)); }
            else stderr_bytes(p,n);
            p+=n;
        }
    }
    stderr_text("\"");
}
_Noreturn void rt_fail(uint32_t code,const RtOrigin *origin) {
    const RtOrigin *o=origin?origin:&rt_origin_generated;
    stderr_text("Error de ejecución ["); stderr_text(error_name(code)); stderr_text("] "); stderr_path(o->file);
    char numbers[64]; int n=snprintf(numbers,sizeof(numbers),":%" PRId64 ":%" PRId64 ": ",o->line,o->column);
    stderr_bytes(numbers,(size_t)n); stderr_text(error_name(code)); stderr_text("\n");
    rt_memory_release_all(); _Exit(1);
}
void rt_program_init(void) { RtConsoleError e; uint32_t code=rt_console_v1_init(&e); if(code) rt_fail(code,&rt_origin_generated); }
void rt_program_shutdown(void) { RtConsoleError e; uint32_t code=rt_console_v1_shutdown(&e); rt_memory_release_all(); if(code) rt_fail(code,&rt_origin_generated); }
int rt_run_program(const RtClass *entry_class,RtMethodEntry initializer,RtMethodEntry main_method) {
    rt_program_init();
    RtObject *receiver=rt_object_new(entry_class,&rt_origin_generated);
    if(initializer) initializer(receiver);
    main_method(receiver);
    rt_program_shutdown(); return 0;
}
void rt_check_null(const void *value,const RtOrigin *o) { if(!value) rt_fail(RT_NULL_REFERENCE,o); }
void rt_check_index(const RtList *list,int64_t index,const RtOrigin *o) {
    rt_check_null(list,o); if(index<0 || (uint64_t)index>=list->length) rt_fail(RT_BAD_INDEX,o);
}
int64_t rt_div_int(int64_t a,int64_t b,const RtOrigin *o) {
    if(b==0) rt_fail(RT_ZERO_DIVISION,o);
    if(a==INT64_MIN && b==-1) return INT64_MIN;
    return a/b;
}
double rt_div_float(double a,double b,const RtOrigin *o) { if(b==0.0) rt_fail(RT_ZERO_DIVISION,o); return a/b; }
uint64_t rt_string_equal(const RtString *a,const RtString *b) {
    return a==b || (a && b && a->length==b->length && memcmp(a->bytes,b->bytes,(size_t)a->length)==0);
}
typedef struct { unsigned char *p; size_t n,capacity; const RtOrigin *o; } Builder;
static void append(Builder *b,const unsigned char *p,uint64_t n) {
    if(n>SIZE_MAX-b->n) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,b->o);
    size_t needed=b->n+(size_t)n;
    if(needed>b->capacity) {
        size_t capacity=b->capacity?b->capacity:64;
        while(capacity<needed) { if(capacity>SIZE_MAX/2) { capacity=needed; break; } capacity*=2; }
        unsigned char *grown=realloc(b->p,capacity); if(!grown) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,b->o);
        b->p=grown; b->capacity=capacity;
    }
    if(n) memcpy(b->p+b->n,p,(size_t)n);
    b->n=needed;
}
static void literal(Builder *b,const char *s) { append(b,(const unsigned char *)s,strlen(s)); }
typedef struct { const RtList *list; uint64_t index; } FormatFrame;
RtString *rt_value_to_string(uint64_t value,const RtType *type,const RtOrigin *o) {
    if(type->kind==RT_STRING && value) return (RtString *)(uintptr_t)value;
    Builder b={NULL,0,0,o}; FormatFrame *stack=NULL; size_t depth=0,capacity=0;
    for(;;) {
        char text[32];
        switch(type->kind) {
            case RT_INT: { int64_t i; memcpy(&i,&value,8); int n=snprintf(text,sizeof(text),"%" PRId64,i); append(&b,(unsigned char *)text,(uint64_t)n); break; }
            case RT_FLOAT: { double d; memcpy(&d,&value,8); size_t n=rt_double_to_java11(d,text); append(&b,(unsigned char *)text,n); break; }
            case RT_BOOL: literal(&b,value?"true":"false"); break;
            case RT_STRING: {
                const RtString *s=(const RtString *)(uintptr_t)value;
                if(s) append(&b,s->bytes,s->length); else literal(&b,"null");
                break;
            }
            case RT_NULL: literal(&b,"null"); break;
            case RT_OBJECT: {
                const RtObject *obj=(const RtObject *)(uintptr_t)value;
                if(obj) { literal(&b,"<"); append(&b,obj->klass->name->bytes,obj->klass->name->length); literal(&b,">"); }
                else literal(&b,"null");
                break;
            }
            case RT_LIST: {
                const RtList *list=(const RtList *)(uintptr_t)value;
                if(!list) { literal(&b,"null"); break; }
                literal(&b,"["); if(!list->length) { literal(&b,"]"); break; }
                if(depth==capacity) {
                    if(capacity>SIZE_MAX/(2*sizeof(*stack))) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
                    capacity=capacity?capacity*2:16; FormatFrame *grown=realloc(stack,capacity*sizeof(*stack));
                    if(!grown) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
                    stack=grown;
                }
                stack[depth++]=(FormatFrame){list,0}; value=list->items[0]; type=list->element; continue;
            }
            default: abort(); /* descriptor inválido: error del compilador, no del lenguaje */
        }
        while(depth) {
            FormatFrame *f=&stack[depth-1];
            if(++f->index<f->list->length) { literal(&b,", "); value=f->list->items[f->index]; type=f->list->element; break; }
            literal(&b,"]"); depth--;
        }
        if(!depth) break;
    }
    RtString *s=rt_string_new(b.p,b.n,o); free(b.p); free(stack); return s;
}
RtString *rt_concat(const RtString *a,const RtString *b,const RtOrigin *o) {
    static const unsigned char nil[]="null";
    uint64_t na=a?a->length:4, nb=b?b->length:4;
    if(na>UINT64_MAX-nb || na+nb>SIZE_MAX-17) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
    void *p; if(rt_memory_try_alloc(17+na+nb,&p)) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
    RtString *s=p; s->length=na+nb; s->reserved=0;
    memcpy(s->bytes,a?a->bytes:nil,(size_t)na); memcpy(s->bytes+na,b?b->bytes:nil,(size_t)nb); s->bytes[s->length]=0; return s;
}
void rt_lang_print(RtObject *r,RtString *s,uint64_t lf,const RtOrigin *o) { rt_check_null(r,o); rt_check_null(s,o); RtConsoleError e; uint32_t code=rt_console_v1_write(s,(uint32_t)lf,&e); if(code) rt_fail(code,o); }
int64_t rt_lang_read_int(RtObject *r,const RtOrigin *o) { rt_check_null(r,o); int64_t v; RtConsoleError e; uint32_t code=rt_console_v1_read_int(&v,&e); if(code) rt_fail(code,o); return v; }
double rt_lang_read_float(RtObject *r,const RtOrigin *o) { rt_check_null(r,o); double v; RtConsoleError e; uint32_t code=rt_console_v1_read_float(&v,&e); if(code) rt_fail(code,o); return v; }
uint64_t rt_lang_read_bool(RtObject *r,const RtOrigin *o) { rt_check_null(r,o); uint32_t v; RtConsoleError e; uint32_t code=rt_console_v1_read_bool(&v,&e); if(code) rt_fail(code,o); return v; }
RtString *rt_lang_read_line(RtObject *r,const RtOrigin *o) { rt_check_null(r,o); RtString *v; RtConsoleError e; uint32_t code=rt_console_v1_read_line(&v,&e); if(code!=RT_CONSOLE_OK && code!=RT_CONSOLE_EOF) rt_fail(code,o); return v; }
