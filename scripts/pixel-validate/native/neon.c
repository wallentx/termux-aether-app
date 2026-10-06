#include "kernels.h"
#include <arm_neon.h>

uint64_t neon_sum(const uint8_t *a, const uint8_t *b, size_t n) {
    uint64_t total = 0;
    size_t i = 0;
    for (; n - i >= 16; i += 16)
        total += vaddlvq_u8(vabdq_u8(vld1q_u8(a + i), vld1q_u8(b + i)));
    return total + scalar_sum(a + i, b + i, n - i);
}
