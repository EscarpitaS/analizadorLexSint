#include "rt_runtime.h"
#include "rt_testing.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static void matches(const RtString *s,const char *text) { assert(s->length==strlen(text)); assert(!memcmp(s->bytes,text,(size_t)s->length)); }
static uint64_t pointer(const void *p) { return (uint64_t)(uintptr_t)p; }
int main(void) {
    rt_program_init();
    assert(rt_console_v1_abi_version()==1);
    RtString *name=rt_string_new((const unsigned char *)"D",1,&rt_origin_generated);
    const RtType *fields[]={&rt_type_int,&rt_type_float,&rt_type_bool,&rt_type_string};
    RtClass klass={name,48,NULL,NULL,4,fields}; RtObject *a=rt_object_new(&klass,&rt_origin_generated), *b=rt_object_new(&klass,&rt_origin_generated);
    assert(a!=b && a->klass==&klass); for(int i=0;i<4;i++) assert(a->fields[i]==0);
    RtObject *alias=a; alias->fields[0]=7; assert(a->fields[0]==7 && b->fields[0]==0);
    RtType object_type={RT_OBJECT,NULL,&klass}; matches(rt_value_to_string(pointer(a),&object_type,&rt_origin_generated),"<D>");
    matches(rt_value_to_string(0,&object_type,&rt_origin_generated),"null");
    unsigned char bytes[]={'x',0,0xc3,0xb1}; RtString *s=rt_string_new(bytes,sizeof(bytes),&rt_origin_generated);
    assert(s->length==4 && s->bytes[4]==0 && !memcmp(s->bytes,bytes,4));
    assert(rt_string_equal(s,rt_string_new(bytes,4,&rt_origin_generated)));
    assert(!rt_string_equal(s,NULL) && rt_string_equal(NULL,NULL));
    RtString *joined=rt_concat(NULL,s,&rt_origin_generated); assert(joined->length==8 && !memcmp(joined->bytes,"nullx\0\xc3\xb1",8));
    assert(rt_value_to_string(pointer(s),&rt_type_string,&rt_origin_generated)==s);
    RtList *xs=rt_list_new(3,&rt_type_int,&rt_origin_generated); xs->items[0]=1; xs->items[1]=2; xs->items[2]=3;
    RtList *ys=xs; ys->items[1]=7; assert(xs->items[1]==7); rt_check_index(xs,2,&rt_origin_generated);
    RtType list_int={RT_LIST,&rt_type_int,NULL}; matches(rt_value_to_string(pointer(xs),&list_int,&rt_origin_generated),"[1, 7, 3]");
    RtList *empty=rt_list_new(0,&rt_type_int,&rt_origin_generated); matches(rt_value_to_string(pointer(empty),&list_int,&rt_origin_generated),"[]");
    RtList *rows=rt_list_new(2,&list_int,&rt_origin_generated); rows->items[0]=pointer(xs); rows->items[1]=pointer(empty);
    RtType matrix={RT_LIST,&list_int,NULL}; matches(rt_value_to_string(pointer(rows),&matrix,&rt_origin_generated),"[[1, 7, 3], []]");
    /* Profundidad extrema: el formateador no recurre en la pila C. */
    RtList *current=empty; void *types_storage;
    assert(rt_memory_try_alloc(4096*sizeof(RtType),&types_storage)==0);
    RtType *types=types_storage;
    for(int i=0;i<4096;i++) {
        types[i]=(RtType){RT_LIST,i?&types[i-1]:&rt_type_int,NULL};
        RtList *next=rt_list_new(1,&types[i],&rt_origin_generated); next->items[0]=pointer(current); current=next;
    }
    RtType nested={RT_LIST,&types[4095],NULL}; RtString *deep=rt_value_to_string(pointer(current),&nested,&rt_origin_generated); assert(deep->length==8194);
    assert(rt_div_int(INT64_MIN,-1,&rt_origin_generated)==INT64_MIN);
    assert(rt_div_int(-7,2,&rt_origin_generated)==-3); assert(rt_div_float(7.0,2.0,&rt_origin_generated)==3.5);
    char text[32]; assert(rt_double_to_java11(-0.0,text)==4 && !strcmp(text,"-0.0"));
    assert(rt_double_to_java11(nextafter(0.0,1.0),text)==8 && !strcmp(text,"4.9E-324"));
    rt_test_memory_limit(0); void *out=(void *)(uintptr_t)123; assert(rt_memory_try_alloc(1,&out)==RT_CONSOLE_OUT_OF_MEMORY && out==(void *)(uintptr_t)123);
    RtString *string_out=(RtString *)(uintptr_t)123; assert(rt_string_try_new(bytes,4,&string_out)==RT_CONSOLE_OUT_OF_MEMORY && string_out==(RtString *)(uintptr_t)123);
    assert(rt_memory_try_alloc(UINT64_MAX,&out)==RT_CONSOLE_OUT_OF_MEMORY);
    rt_program_shutdown(); puts("OK: layout, aliasing, strings/NUL, formato anidado, división y memoria nativos."); return 0;
}
