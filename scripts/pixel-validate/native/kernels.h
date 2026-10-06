#ifndef PIXEL_KERNELS_H
#define PIXEL_KERNELS_H
#include <stddef.h>
#include <stdint.h>
typedef uint64_t (*kernel_fn)(const uint8_t *, const uint8_t *, size_t);
uint64_t scalar_sum(const uint8_t *a, const uint8_t *b, size_t n);
uint64_t neon_sum(const uint8_t *a, const uint8_t *b, size_t n);
uint64_t sve2_sum(const uint8_t *a, const uint8_t *b, size_t n);
#endif
