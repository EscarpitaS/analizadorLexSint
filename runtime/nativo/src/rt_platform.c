#define _GNU_SOURCE
#include "rt_platform.h"
#include <errno.h>
#include <limits.h>
#include <locale.h>
#include <stdint.h>
#include <stdlib.h>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
static _locale_t numeric_locale;
static unsigned char pending[4];
static size_t next_byte, pending_size;
static WCHAR saved_unit;
static int has_saved_unit;
static int io_error(DWORD error) {
    errno=error==ERROR_BROKEN_PIPE || error==ERROR_NO_DATA || error==ERROR_PIPE_NOT_CONNECTED ? EPIPE : error==ERROR_INVALID_HANDLE ? EBADF :
          error==ERROR_NOT_ENOUGH_MEMORY || error==ERROR_OUTOFMEMORY ? ENOMEM : EIO;
    return -1;
}
int rt_platform_init(void) {
    if(!numeric_locale) numeric_locale=_create_locale(LC_NUMERIC,"C");
    next_byte=pending_size=0; has_saved_unit=0;
    return numeric_locale?0:(errno=ENOMEM,-1);
}
void rt_platform_shutdown(void) { if(numeric_locale) _free_locale(numeric_locale); numeric_locale=NULL; }
double rt_platform_parse_double(const char *p,char **end) { return _strtod_l(p,end,numeric_locale); }
static int unit(HANDLE input,WCHAR *out) {
    if(has_saved_unit) { *out=saved_unit; has_saved_unit=0; return 1; }
    DWORD read=0;
    if(!ReadConsoleW(input,out,1,&read,NULL)) return io_error(GetLastError());
    return read?1:0;
}
ptrdiff_t rt_platform_read(unsigned char *buffer,size_t size) {
    if(!size) return 0;
    HANDLE input=GetStdHandle(STD_INPUT_HANDLE); DWORD mode;
    if(!GetConsoleMode(input,&mode)) {
        DWORD read=0;
        if(!ReadFile(input,buffer,(DWORD)(size>INT_MAX?INT_MAX:size),&read,NULL)) {
            DWORD error=GetLastError(); if(error==ERROR_BROKEN_PIPE || error==ERROR_HANDLE_EOF || error==ERROR_NO_DATA || error==ERROR_PIPE_NOT_CONNECTED) return 0;
            return io_error(error);
        }
        return (ptrdiff_t)read;
    }
    if(next_byte==pending_size) {
        WCHAR utf16[2]; int got=unit(input,&utf16[0]); if(got<=0) return got;
        int count=1, invalid=0;
        if(utf16[0]>=0xd800 && utf16[0]<=0xdbff) {
            got=unit(input,&utf16[1]); if(got<0) return -1;
            if(!got || utf16[1]<0xdc00 || utf16[1]>0xdfff) {
                invalid=1; if(got) { saved_unit=utf16[1]; has_saved_unit=1; }
            } else count=2;
        } else if(utf16[0]>=0xdc00 && utf16[0]<=0xdfff) invalid=1;
        // 0xff mantiene el rechazo UTF-8 en el analizador común, tras consumir la línea.
        if(invalid) { pending[0]=0xff; pending_size=1; }
        else {
            int bytes=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,utf16,count,(char *)pending,4,NULL,NULL);
            if(!bytes) return io_error(GetLastError());
            pending_size=(size_t)bytes;
        }
        next_byte=0;
    }
    size_t n=pending_size-next_byte; if(n>size) n=size;
    for(size_t i=0;i<n;i++) buffer[i]=pending[next_byte++];
    return (ptrdiff_t)n;
}
ptrdiff_t rt_platform_write(int channel,const unsigned char *buffer,size_t size) {
    if(!size) return 0;
    HANDLE output=GetStdHandle(channel==2?STD_ERROR_HANDLE:STD_OUTPUT_HANDLE); DWORD mode,written=0;
    if(!GetConsoleMode(output,&mode)) {
        if(!WriteFile(output,buffer,(DWORD)(size>INT_MAX?INT_MAX:size),&written,NULL)) return io_error(GetLastError());
        return (ptrdiff_t)written;
    }
    // Lotes UTF-8 completos; los reintentos físicos continúan por unidades UTF-16.
    size_t n=size>16384?16384:size;
    if(n<size) while(n && (buffer[n]&0xc0)==0x80) n--;
    WCHAR utf16[16384];
    int count=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,(const char *)buffer,(int)n,utf16,16384);
    if(!count) return io_error(GetLastError());
    DWORD done=0;
    while(done<(DWORD)count) {
        if(!WriteConsoleW(output,utf16+done,(DWORD)count-done,&written,NULL)) return io_error(GetLastError());
        if(!written) { errno=EIO; return -1; }
        done+=written;
    }
    return (ptrdiff_t)n;
}
#else
#include <signal.h>
#include <unistd.h>
static locale_t numeric_locale;
int rt_platform_init(void) {
    struct sigaction action={0}; action.sa_handler=SIG_IGN;
    if(sigemptyset(&action.sa_mask) || sigaction(SIGPIPE,&action,NULL)) return -1;
    if(!numeric_locale) numeric_locale=newlocale(LC_NUMERIC_MASK,"C",(locale_t)0);
    return numeric_locale?0:-1;
}
void rt_platform_shutdown(void) { if(numeric_locale) freelocale(numeric_locale); numeric_locale=(locale_t)0; }
double rt_platform_parse_double(const char *p,char **end) { return strtod_l(p,end,numeric_locale); }
ptrdiff_t rt_platform_read(unsigned char *buffer,size_t size) { return read(STDIN_FILENO,buffer,size); }
ptrdiff_t rt_platform_write(int channel,const unsigned char *buffer,size_t size) { return write(channel==2?STDERR_FILENO:STDOUT_FILENO,buffer,size); }
#endif
