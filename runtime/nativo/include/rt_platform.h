#ifndef ANALIZADOR_RT_PLATFORM_H
#define ANALIZADOR_RT_PLATFORM_H
#include <stddef.h>
/* Frontera del sistema: bytes UTF-8, sin traducción de texto del CRT. */
int rt_platform_init(void);
void rt_platform_shutdown(void);
ptrdiff_t rt_platform_read(unsigned char *buffer, size_t size);
ptrdiff_t rt_platform_write(int channel, const unsigned char *buffer, size_t size);
double rt_platform_parse_double(const char *text, char **end);
#endif
