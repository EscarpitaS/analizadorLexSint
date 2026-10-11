#include "rt_runtime.h"
#include "rt_testing.h"
#include <assert.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

static struct {
    const unsigned char *input;
    size_t length,position,output_length,output_capacity;
    unsigned char *output;
    int kind,fail_operation,operation;
    size_t after;
    int interrupted_read,interrupted_write,interrupted_flush;
} probe;
/* kind: 0 normal, 1 lectura, 2 escritura parcial, 3 flush, 4 reserva,
 * 5 EINTR, 6 write parcial sin fallo, 7 write devuelve cero. */
static ptrdiff_t probe_read(void *ctx,unsigned char *p,size_t n) {
    (void)ctx;
    if(probe.kind==5 && !probe.interrupted_read++) { errno=EINTR; return -1; }
    if(probe.kind==1 && probe.operation==probe.fail_operation && probe.position>=probe.after) { errno=EIO; return -1; }
    if(probe.position==probe.length) return 0;
    if(n>probe.length-probe.position) n=probe.length-probe.position;
    memcpy(p,probe.input+probe.position,n); probe.position+=n; return (ptrdiff_t)n;
}
static ptrdiff_t probe_write(void *ctx,const unsigned char *p,size_t n) {
    (void)ctx;
    if(probe.kind==5 && !probe.interrupted_write++) { errno=EINTR; return -1; }
    if(probe.kind==7) return 0;
    if(probe.kind==2 && probe.operation==probe.fail_operation) {
        if(probe.output_length>=probe.after) { errno=EIO; return -1; }
        if(n>probe.after-probe.output_length) n=probe.after-probe.output_length;
    }
    if((probe.kind==5 || probe.kind==6) && n>1) n=1;
    if(probe.output_length+n>probe.output_capacity) {
        size_t cap=(probe.output_length+n)*2; void *grown=realloc(probe.output,cap); assert(grown); probe.output=grown; probe.output_capacity=cap;
    }
    memcpy(probe.output+probe.output_length,p,n); probe.output_length+=n; return (ptrdiff_t)n;
}
static int probe_flush(void *ctx) {
    (void)ctx;
    if(probe.kind==5 && !probe.interrupted_flush++) { errno=EINTR; return -1; }
    if(probe.kind==3 && probe.operation==probe.fail_operation) { errno=EIO; return -1; }
    return 0;
}
void rt_probe_begin(const unsigned char *bytes,size_t length,int kind,int operation,size_t after) {
    rt_memory_release_all(); rt_test_memory_limit(UINT64_MAX); rt_test_fail_returned_string(0);
    free(probe.output); memset(&probe,0,sizeof(probe));
    probe.input=bytes; probe.length=length; probe.kind=kind; probe.fail_operation=operation; probe.after=after;
    RtTestChannels channels={probe_read,probe_write,probe_flush,NULL}; rt_test_channels(&channels);
    RtConsoleError e; assert(rt_console_v1_init(&e)==RT_CONSOLE_OK);
}
void rt_probe_operation(int operation) { probe.operation=operation; rt_test_fail_returned_string(probe.kind==4 && operation==probe.fail_operation); }
const unsigned char *rt_probe_output(void) { return probe.output; }
size_t rt_probe_output_length(void) { return probe.output_length; }
