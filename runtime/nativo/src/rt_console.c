#define _GNU_SOURCE
#include "rt_runtime.h"
#include "rt_testing.h"
#include <errno.h>
#include <fenv.h>
#include "rt_platform.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

static int eof;
#ifdef RT_TESTING
static RtTestChannels test_channels;
static int fail_returned_string;
void rt_test_channels(const RtTestChannels *c) { test_channels=c?*c:(RtTestChannels){0}; }
void rt_test_fail_returned_string(int fail) { fail_returned_string=fail; }
#endif
static ptrdiff_t input(unsigned char *buffer,size_t n) {
#ifdef RT_TESTING
    if(test_channels.read) return test_channels.read(test_channels.context,buffer,n);
#endif
    return rt_platform_read(buffer,n);
}
static ptrdiff_t output(const unsigned char *buffer,size_t n) {
#ifdef RT_TESTING
    if(test_channels.write) return test_channels.write(test_channels.context,buffer,n);
#endif
    return rt_platform_write(1,buffer,n);
}
/* No hay un buffer de salida en producción: write transfiere todo antes del retorno. */
static int flush_output(void) {
#ifdef RT_TESTING
    if(test_channels.flush) return test_channels.flush(test_channels.context);
#endif
    return 0;
}
static RtConsoleStatus status(RtConsoleError *e,uint32_t code,int cause,uint64_t offset) {
    *e=(RtConsoleError){code,cause,offset}; return code;
}
uint32_t rt_console_v1_abi_version(void) { return RT_CONSOLE_ABI_VERSION; }
RtConsoleStatus rt_console_v1_init(RtConsoleError *e) {
    if(rt_platform_init()) return status(e,errno==ENOMEM?RT_CONSOLE_OUT_OF_MEMORY:RT_CONSOLE_WRITE_ERROR,errno,0);
    if(fesetround(FE_TONEAREST)) return status(e,RT_CONSOLE_READ_ERROR,0,0);
    eof=0; return status(e,RT_CONSOLE_OK,0,0);
}
RtConsoleStatus rt_console_v1_shutdown(RtConsoleError *e) {
    int result;
    do { result=flush_output(); } while(result<0 && errno==EINTR);
    int cause=result<0?errno:0;
    rt_platform_shutdown();
    return status(e,result<0?RT_CONSOLE_WRITE_ERROR:RT_CONSOLE_OK,cause,0);
}
static RtConsoleStatus write_all(const unsigned char *p,uint64_t n,RtConsoleError *e) {
    while(n) {
        size_t amount=n>(uint64_t)INT64_MAX?(size_t)INT64_MAX:(size_t)n;
        ptrdiff_t done=output(p,amount);
        if(done<0 && errno==EINTR) continue;
        if(done<=0) return status(e,RT_CONSOLE_WRITE_ERROR,done<0?errno:EIO,0);
        p+=done; n-=(uint64_t)done;
    }
    return RT_CONSOLE_OK;
}
RtConsoleStatus rt_console_v1_write(const RtString *text,uint32_t lf,RtConsoleError *e) {
    RtConsoleStatus code=write_all(text->bytes,text->length,e); if(code) return code;
    if(lf) { static const unsigned char newline='\n'; code=write_all(&newline,1,e); if(code) return code; }
    int result; do { result=flush_output(); } while(result<0 && errno==EINTR);
    return status(e,result<0?RT_CONSOLE_WRITE_ERROR:RT_CONSOLE_OK,result<0?errno:0,0);
}
/* DFA UTF-8: estado entre bytes, fronteras de RFC 3629 y origen 1-based. */
static uint64_t invalid_utf8(const unsigned char *p,size_t n) {
    unsigned remaining=0,lo=0x80,hi=0xbf; size_t start=0;
    for(size_t i=0;i<n;i++) {
        unsigned c=p[i];
        if(remaining) {
            if(c<lo || c>hi) return (uint64_t)i+1;
            remaining--; lo=0x80; hi=0xbf;
        } else if(c<=0x7f) continue;
        else {
            start=i;
            if(c>=0xc2 && c<=0xdf) remaining=1;
            else if(c>=0xe0 && c<=0xef) { remaining=2; if(c==0xe0) lo=0xa0; if(c==0xed) hi=0x9f; }
            else if(c>=0xf0 && c<=0xf4) { remaining=3; if(c==0xf0) lo=0x90; if(c==0xf4) hi=0x8f; }
            else return (uint64_t)i+1;
        }
    }
    return remaining?(uint64_t)start+1:0;
}
/* Consume completo antes de validar UTF-8: límite/I/O tienen precedencia. */
static RtConsoleStatus line(unsigned char **out,size_t *length,RtConsoleError *e) {
    if(eof) return status(e,RT_CONSOLE_EOF,0,0);
    size_t n=0,capacity=256; unsigned char *buffer=malloc(capacity);
    if(!buffer) return status(e,RT_CONSOLE_OUT_OF_MEMORY,0,0);
    int lf=0;
    while(!eof) {
        unsigned char c; ptrdiff_t done;
        do { done=input(&c,1); } while(done<0 && errno==EINTR);
        if(done<0) { int cause=errno; free(buffer); return status(e,RT_CONSOLE_READ_ERROR,cause,0); }
        if(done==0) { eof=1; break; }
        if(c=='\n') { lf=1; break; }
        if(n==RT_CONSOLE_MAX_LINE_BYTES+1) { free(buffer); return status(e,RT_CONSOLE_LINE_TOO_LONG,0,RT_CONSOLE_MAX_LINE_BYTES+1); }
        if(n+1>=capacity) {
            size_t next=capacity*2; if(next>RT_CONSOLE_MAX_LINE_BYTES+2) next=RT_CONSOLE_MAX_LINE_BYTES+2;
            unsigned char *grown=realloc(buffer,next);
            if(!grown) { free(buffer); return status(e,RT_CONSOLE_OUT_OF_MEMORY,0,0); }
            buffer=grown; capacity=next;
        }
        buffer[n++]=c;
        /* Una CR extra puede ser la mitad de CRLF en el límite. */
        if(n>RT_CONSOLE_MAX_LINE_BYTES && c!='\r') { free(buffer); return status(e,RT_CONSOLE_LINE_TOO_LONG,0,RT_CONSOLE_MAX_LINE_BYTES+1); }
    }
    if(lf && n && buffer[n-1]=='\r') n--;
    if(n>RT_CONSOLE_MAX_LINE_BYTES) { free(buffer); return status(e,RT_CONSOLE_LINE_TOO_LONG,0,RT_CONSOLE_MAX_LINE_BYTES+1); }
    if(!lf && n==0 && eof) { free(buffer); return status(e,RT_CONSOLE_EOF,0,0); }
    uint64_t offset=invalid_utf8(buffer,n);
    if(offset) { free(buffer); return status(e,RT_CONSOLE_INVALID_UTF8,0,offset); }
    buffer[n]=0; *out=buffer; *length=n; return status(e,RT_CONSOLE_OK,0,0);
}
RtConsoleStatus rt_console_v1_read_line(RtString **out,RtConsoleError *e) {
    unsigned char *p; size_t n; RtConsoleStatus code=line(&p,&n,e);
    if(code==RT_CONSOLE_EOF) { *out=NULL; return code; }
    if(code) return code;
#ifdef RT_TESTING
    if(fail_returned_string) { free(p); return status(e,RT_CONSOLE_OUT_OF_MEMORY,0,0); }
#endif
    RtString *s; code=rt_string_try_new(p,(uint64_t)n,&s); free(p);
    if(code) return status(e,code,0,0);
    *out=s; return status(e,RT_CONSOLE_OK,0,0);
}
static int space(unsigned c) { return c==32 || c==9 || c==13 || c==11 || c==12; }
static void trim(unsigned char *p,size_t *start,size_t *end) {
    while(*start<*end && space(p[*start])) (*start)++;
    while(*end>*start && space(p[*end-1])) (*end)--;
}
static int digit(unsigned c) { return c>='0' && c<='9'; }
static int decimal(const unsigned char *p,size_t n,int floating) {
    size_t i=0; if(i<n && (p[i]=='+' || p[i]=='-')) i++;
    size_t begin=i; while(i<n && digit(p[i])) i++; if(i==begin) return 0;
    if(floating && i<n && p[i]=='.') { begin=++i; while(i<n && digit(p[i])) i++; if(i==begin) return 0; }
    if(floating && i<n && (p[i]=='e' || p[i]=='E')) {
        i++; if(i<n && (p[i]=='+' || p[i]=='-')) i++;
        begin=i; while(i<n && digit(p[i])) i++; if(i==begin) return 0;
    }
    return i==n;
}
RtConsoleStatus rt_console_v1_read_int(int64_t *out,RtConsoleError *e) {
    unsigned char *p; size_t n; RtConsoleStatus code=line(&p,&n,e); if(code) return code;
    size_t a=0,b=n; trim(p,&a,&b);
    if(!decimal(p+a,b-a,0)) { free(p); return status(e,RT_CONSOLE_INVALID_INPUT,0,0); }
    int neg=p[a]=='-'; if(p[a]=='-' || p[a]=='+') a++;
    uint64_t limit=neg?UINT64_C(9223372036854775808):INT64_MAX, value=0;
    for(size_t i=a;i<b;i++) {
        uint64_t d=p[i]-'0';
        if(value>(limit-d)/10) { free(p); return status(e,RT_CONSOLE_OUT_OF_RANGE,0,0); }
        value=value*10+d;
    }
    free(p); *out=neg?(value==UINT64_C(9223372036854775808)?INT64_MIN:-(int64_t)value):(int64_t)value;
    return status(e,RT_CONSOLE_OK,0,0);
}
RtConsoleStatus rt_console_v1_read_float(double *out,RtConsoleError *e) {
    unsigned char *p; size_t n; RtConsoleStatus code=line(&p,&n,e); if(code) return code;
    size_t a=0,b=n; trim(p,&a,&b);
    if(!decimal(p+a,b-a,1)) { free(p); return status(e,RT_CONSOLE_INVALID_INPUT,0,0); }
    p[b]=0; char *end; errno=0; double value=rt_platform_parse_double((char *)p+a,&end);
    if(end!=(char *)p+b || end==(char *)p+a) { free(p); return status(e,RT_CONSOLE_INVALID_INPUT,0,0); }
    if(!isfinite(value)) { free(p); return status(e,RT_CONSOLE_OUT_OF_RANGE,0,0); }
    /* ERANGE por underflow admite subnormales/cero con signo. */
    free(p); *out=value; return status(e,RT_CONSOLE_OK,0,0);
}
RtConsoleStatus rt_console_v1_read_bool(uint32_t *out,RtConsoleError *e) {
    unsigned char *p; size_t n; RtConsoleStatus code=line(&p,&n,e); if(code) return code;
    size_t a=0,b=n; trim(p,&a,&b); uint32_t value;
    if(b-a==4 && memcmp(p+a,"true",4)==0) value=1;
    else if(b-a==5 && memcmp(p+a,"false",5)==0) value=0;
    else { free(p); return status(e,RT_CONSOLE_INVALID_INPUT,0,0); }
    free(p); *out=value; return status(e,RT_CONSOLE_OK,0,0);
}
