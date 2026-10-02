#include "kernels.h"

/* Compiled separately with loop and SLP vectorization disabled, without LTO. */
uint64_t scalar_sum(const uint8_t *a, const uint8_t *b, size_t n) {
    uint64_t total = 0;
    for (size_t i = 0; i < n; ++i) {
        int delta = (int)a[i] - (int)b[i];
        total += (uint64_t)(delta < 0 ? -delta : delta);
    }
    return total;
}
