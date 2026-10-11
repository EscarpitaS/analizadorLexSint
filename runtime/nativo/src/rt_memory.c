#include "rt_runtime.h"
#include "rt_testing.h"
#include <stdlib.h>
#include <string.h>

typedef struct Allocation { struct Allocation *next; max_align_t alignment; unsigned char data[]; } Allocation;
static Allocation *allocations;
#ifdef RT_TESTING
static uint64_t limit=UINT64_MAX, used;
void rt_test_memory_limit(uint64_t n) { limit=n; used=0; }
#endif
RtConsoleStatus rt_memory_try_alloc(uint64_t n,void **out) {
    if(n>SIZE_MAX-sizeof(Allocation)) return RT_CONSOLE_OUT_OF_MEMORY;
#ifdef RT_TESTING
    if(n>limit-used) return RT_CONSOLE_OUT_OF_MEMORY;
#endif
    Allocation *p=malloc(sizeof(Allocation)+(size_t)n);
    if(!p) return RT_CONSOLE_OUT_OF_MEMORY;
    p->next=allocations; allocations=p;
#ifdef RT_TESTING
    used+=n;
#endif
    *out=p->data; return RT_CONSOLE_OK;
}
void rt_memory_release_all(void) {
    while(allocations) { Allocation *p=allocations; allocations=p->next; free(p); }
#ifdef RT_TESTING
    used=0;
#endif
}
RtConsoleStatus rt_string_try_new(const unsigned char *bytes,uint64_t n,RtString **out) {
    if(n>SIZE_MAX-17) return RT_CONSOLE_OUT_OF_MEMORY;
    void *p; RtConsoleStatus status=rt_memory_try_alloc(17+n,&p);
    if(status) return status;
    RtString *s=p; s->length=n; s->reserved=0; if(n) memcpy(s->bytes,bytes,(size_t)n); s->bytes[n]=0;
    *out=s; return RT_CONSOLE_OK;
}
RtString *rt_string_new(const unsigned char *bytes,uint64_t n,const RtOrigin *o) {
    RtString *s; if(rt_string_try_new(bytes,n,&s)!=RT_CONSOLE_OK) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o); return s;
}
RtObject *rt_object_new(const RtClass *c,const RtOrigin *o) {
    if(c->field_count>(SIZE_MAX-16)/8 || c->size!=16+8*c->field_count) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
    void *p; if(rt_memory_try_alloc(c->size,&p)) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
    memset(p,0,(size_t)c->size); RtObject *r=p; r->vtable=c->vtable; r->klass=c; return r;
}
RtList *rt_list_new(int64_t n,const RtType *element,const RtOrigin *o) {
    if(n<0 || (uint64_t)n>(SIZE_MAX-16)/8) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
    uint64_t bytes=16+8*(uint64_t)n; void *p;
    if(rt_memory_try_alloc(bytes,&p)) rt_fail(RT_CONSOLE_OUT_OF_MEMORY,o);
    memset(p,0,(size_t)bytes); RtList *r=p; r->length=(uint64_t)n; r->element=element; return r;
}
