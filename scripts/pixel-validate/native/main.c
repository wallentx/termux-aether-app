#define _POSIX_C_SOURCE 200809L
#include "kernels.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>

#define INPUT_SIZE (8u * 1024u * 1024u)
#define EXPECTED UINT64_C(705953792)
/* Linux arm64 UAPI: HWCAP_ASIMD, HWCAP_SVE, HWCAP2_SVE2, PR_SVE_GET_VL. */
#define ASIMD (1UL << 1)
#define SVE (1UL << 22)
#define SVE2 (1UL << 1)

static double now(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) { perror("clock_gettime"); exit(1); }
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static int supported(const char *variant) {
    if (!strcmp(variant, "scalar")) return 1;
    unsigned long caps = getauxval(AT_HWCAP);
    if (!strcmp(variant, "neon")) return (caps & ASIMD) != 0;
    if (!strcmp(variant, "sve2"))
        return (caps & SVE) && (getauxval(AT_HWCAP2) & SVE2) && prctl(51, 0, 0, 0, 0) >= 0;
    return 0;
}

static kernel_fn kernel(const char *variant) {
    if (!strcmp(variant, "scalar")) return scalar_sum;
    if (!strcmp(variant, "neon")) return neon_sum;
    return sve2_sum;
}

static int self_test(kernel_fn function) {
    /* Independent fixed-answer extreme and mixed fixtures. */
    uint8_t a[513], b[513];
    memset(a, 0, sizeof(a));
    memset(b, 255, sizeof(b));
    if (function(a, b, 0) != 0 || function(a, b, 513) != 130815) return 0;
    const uint8_t x[] = {0, 255, 10, 200}, y[] = {255, 0, 20, 100};
    if (function(x, y, 4) != 620) return 0;
    for (size_t i = 0; i < sizeof(a); ++i) {
        a[i] = (uint8_t)(i * 17 + 13); b[i] = (uint8_t)(i * 29 + 7);
    }
    for (size_t offset = 0; offset < 4; ++offset)
        for (size_t n = 0; n <= 509; ++n)
            if (function(a + offset, b + offset, n) != scalar_sum(a + offset, b + offset, n)) return 0;

    /* Place tails directly before unreadable guard pages: accidental wide
     * overreads must fail in CI/device tests, not hide inside padded fixtures. */
    long page = sysconf(_SC_PAGESIZE);
    if (page < 1024) return 0;
    int fd = open("/dev/zero", O_RDWR);
    if (fd < 0) return 0;
    uint8_t *left = mmap(NULL, (size_t)page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    uint8_t *right = mmap(NULL, (size_t)page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    close(fd);
    if (left == MAP_FAILED || right == MAP_FAILED) {
        if (left != MAP_FAILED) munmap(left, (size_t)page * 2);
        if (right != MAP_FAILED) munmap(right, (size_t)page * 2);
        return 0;
    }
    int ok = !mprotect(left + page, (size_t)page, PROT_NONE)
          && !mprotect(right + page, (size_t)page, PROT_NONE);
    memset(left, 0, (size_t)page); memset(right, 255, (size_t)page);
    for (size_t n = 0; ok && n <= 513; ++n)
        ok = function(left + page - n, right + page - n, n) == n * UINT64_C(255);
    munmap(left, (size_t)page * 2); munmap(right, (size_t)page * 2);
    return ok;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--self-test")) {
        const char *variants[] = {"scalar", "neon", "sve2"};
        for (size_t i = 0; i < 3; ++i) {
            int available = supported(variants[i]);
            if (available && !self_test(kernel(variants[i]))) return 1;
            printf("%s %s\n", variants[i], available ? "PASS" : "SKIP");
        }
        return 0;
    }
    if (argc != 3 || (strcmp(argv[1], "scalar") && strcmp(argv[1], "neon") && strcmp(argv[1], "sve2"))) {
        fprintf(stderr, "usage: pixel-simd-bench scalar|neon|sve2 SECONDS (0.1-5), or --self-test\n");
        return 2;
    }
    char *end;
    errno = 0;
    double seconds = strtod(argv[2], &end);
    if (errno || end == argv[2] || *end || !isfinite(seconds) || seconds < 0.1 || seconds > 5) return 2;
    const char *variant = argv[1];
    if (!supported(variant)) {
        printf("{\"schema_version\":1,\"workload\":\"byte_absdiff_sum\",\"variant\":\"%s\","
               "\"status\":\"SKIP\",\"reason\":\"ISA or thread vector state unavailable\"}\n", variant);
        return 0;
    }
    kernel_fn function = kernel(variant);
    if (!self_test(function)) { fprintf(stderr, "kernel self-test failed\n"); return 1; }
    uint8_t *a = malloc(INPUT_SIZE), *b = malloc(INPUT_SIZE);
    if (!a || !b) { free(a); free(b); return 1; }
    for (size_t i = 0; i < INPUT_SIZE; ++i) {
        a[i] = (uint8_t)(i * 17 + 13); b[i] = (uint8_t)(i * 29 + 7);
    }
    if (function(a, b, INPUT_SIZE) != EXPECTED) { free(a); free(b); return 1; }
    uint64_t iterations = 0, checksum = 0;
    double start = now(), elapsed;
    do {
        checksum = function(a, b, INPUT_SIZE);
        if (checksum != EXPECTED) { free(a); free(b); return 1; }
        ++iterations;
        elapsed = now() - start;
    } while (elapsed < seconds);
    int vector_bytes = !strcmp(variant, "sve2") ? prctl(51, 0, 0, 0, 0) & 0xffff
                     : !strcmp(variant, "neon") ? 16 : 0;
    printf("{\"schema_version\":1,\"workload\":\"byte_absdiff_sum\",\"variant\":\"%s\","
           "\"status\":\"PASS\",\"correctness\":\"PASS\",\"checksum\":%" PRIu64 ","
           "\"bytes_per_iteration\":%u,\"iterations\":%" PRIu64 ",\"elapsed_seconds\":%.9f,"
           "\"vector_length_bytes\":%d}\n", variant, checksum, INPUT_SIZE * 2, iterations, elapsed, vector_bytes);
    free(a); free(b);
    return 0;
}
