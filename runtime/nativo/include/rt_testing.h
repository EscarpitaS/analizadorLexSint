#ifndef ANALIZADOR_RT_TESTING_H
#define ANALIZADOR_RT_TESTING_H
/* Solo compilaciones de prueba. Estos hooks no forman parte del ABI de producción. */
#ifdef RT_TESTING
#include <stddef.h>
#include <stdint.h>
typedef struct RtTestChannels {
    ptrdiff_t (*read)(void *, unsigned char *, size_t);
    ptrdiff_t (*write)(void *, const unsigned char *, size_t);
    int (*flush)(void *);
    void *context;
} RtTestChannels;
void rt_test_channels(const RtTestChannels *channels);
void rt_test_memory_limit(uint64_t bytes);
void rt_test_fail_returned_string(int fail);
#endif
#endif
