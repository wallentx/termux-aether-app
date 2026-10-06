#include "kernels.h"
#include <arm_sve.h>

/* ACLE SVE2 widening absolute differences; inactive byte lanes load as zero.
 * Reduce every vector, so each uint16 lane is at most 510 (no accumulator wrap).
 * No assumption about vector length, including odd-length tails. */
uint64_t sve2_sum(const uint8_t *a, const uint8_t *b, size_t n) {
    uint64_t total = 0;
    for (size_t i = 0; i < n; i += svcntb()) {
        svbool_t active = svwhilelt_b8((uint64_t)i, (uint64_t)n);
        svuint8_t av = svld1_u8(active, a + i);
        svuint8_t bv = svld1_u8(active, b + i);
        svuint16_t sums = svabalb_u16(svdup_u16(0), av, bv);
        sums = svabalt_u16(sums, av, bv);
        total += svaddv_u16(svptrue_b16(), sums);
    }
    return total;
}
