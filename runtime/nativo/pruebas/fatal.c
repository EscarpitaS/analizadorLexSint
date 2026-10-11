#define _POSIX_C_SOURCE 200809L
#include "rt_runtime.h"
#include "rt_testing.h"
#include <assert.h>
#include <string.h>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define STDOUT_FILENO 1
#define STDERR_FILENO 2
#else
#include <signal.h>
#include <unistd.h>
#endif

static const RtOrigin source={"ruta\n\"ñ😀\t\\\177\302\200",3,7};
static void broken(int fd) {
#ifdef _WIN32
    HANDLE input,output; assert(CreatePipe(&input,&output,NULL,0)); assert(CloseHandle(input));
    assert(SetStdHandle(fd==STDOUT_FILENO?STD_OUTPUT_HANDLE:STD_ERROR_HANDLE,output));
#else
    int p[2]; assert(pipe(p)==0); assert(close(p[0])==0); assert(dup2(p[1],fd)==fd); assert(close(p[1])==0);
#endif
}
int main(int argc,char **argv) {
    assert(argc==2); rt_program_init();
    RtClass klass={NULL,16,NULL,NULL,0,NULL}; RtObject *receiver=rt_object_new(&klass,&source);
    if(!strcmp(argv[1],"pipe") || !strcmp(argv[1],"stderr")) {
        broken(STDOUT_FILENO); if(!strcmp(argv[1],"stderr")) broken(STDERR_FILENO);
        RtString *s=rt_string_new((const unsigned char *)"hola",4,&source); rt_lang_print(receiver,s,1,&source);
    } else if(!strcmp(argv[1],"null")) rt_lang_read_int(NULL,&source);
    else if(!strcmp(argv[1],"index")) rt_check_index(rt_list_new(1,&rt_type_int,&source),-1,&source);
    else if(!strcmp(argv[1],"index_null")) rt_check_index(NULL,-1,&source);
    else if(!strcmp(argv[1],"div")) rt_div_int(7,0,&source);
    else if(!strcmp(argv[1],"div_float")) rt_div_float(1.0,-0.0,&source);
    else if(!strcmp(argv[1],"memory")) { rt_test_memory_limit(0); rt_object_new(&klass,&source); }
    else if(!strcmp(argv[1],"size")) rt_list_new(INT64_MAX,&rt_type_int,&source);
    else if(!strcmp(argv[1],"read")) rt_lang_read_int(receiver,&source);
    else if(!strcmp(argv[1],"signal")) {
#ifndef _WIN32
        struct sigaction before; assert(sigaction(SIGPIPE,NULL,&before)==0); assert(before.sa_handler==SIG_IGN);
        rt_program_shutdown(); assert(sigaction(SIGPIPE,NULL,&before)==0); assert(before.sa_handler==SIG_IGN);
#else
        rt_program_shutdown();
#endif
        return 0;
    } else return 2;
    rt_program_shutdown(); return 99; /* Ninguna ruta fatal debe volver. */
}
