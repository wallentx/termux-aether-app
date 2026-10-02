#define _GNU_SOURCE
#include <asm/hwcap.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/auxv.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef __aarch64__
#error "The Pixel probe is ARM64-only"
#endif

static jstring finish_json(JNIEnv *env, FILE *stream, char **buffer) {
    if (fclose(stream) != 0) {
        free(*buffer);
        return (*env)->NewStringUTF(env, "{\"error\":\"JSON allocation failed\"}");
    }
    jstring result = (*env)->NewStringUTF(env, *buffer);
    free(*buffer);
    return result;
}

JNIEXPORT jstring JNICALL Java_com_termux_terminal_PixelProbeProcess_capabilities(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    errno = 0;
    unsigned long hwcap = getauxval(AT_HWCAP);
    int hwcap_errno = errno;
    errno = 0;
    unsigned long hwcap2 = getauxval(AT_HWCAP2);
    int hwcap2_errno = errno;
    int sve = -1, sme = -1, sve_errno = 0, sme_errno = 0;
    if (hwcap & HWCAP_SVE) {
        errno = 0;
        sve = prctl(PR_SVE_GET_VL, 0L, 0L, 0L, 0L);
        sve_errno = sve < 0 ? errno : 0;
    }
    if (hwcap2 & HWCAP2_SME) {
        errno = 0;
        sme = prctl(PR_SME_GET_VL, 0L, 0L, 0L, 0L);
        sme_errno = sme < 0 ? errno : 0;
    }
    char *buffer = NULL;
    size_t size = 0;
    FILE *stream = open_memstream(&buffer, &size);
    if (!stream) return (*env)->NewStringUTF(env, "{\"error\":\"open_memstream failed\"}");
    fprintf(stream, "{\"architecture\":\"aarch64\",\"hwcap\":\"0x%lx\","
        "\"hwcap_errno\":%d,\"hwcap2\":\"0x%lx\",\"hwcap2_errno\":%d,"
        "\"page_size\":%ld,\"features\":{", hwcap, hwcap_errno, hwcap2,
        hwcap2_errno, sysconf(_SC_PAGESIZE));
    struct feature { const char *name; unsigned long value; unsigned long mask; };
    const struct feature features[] = {
        {"asimd", hwcap, HWCAP_ASIMD}, {"asimdhp", hwcap, HWCAP_ASIMDHP},
        {"asimddp", hwcap, HWCAP_ASIMDDP}, {"asimdfhm", hwcap, HWCAP_ASIMDFHM},
        {"sve", hwcap, HWCAP_SVE}, {"sve2", hwcap2, HWCAP2_SVE2},
        {"i8mm", hwcap2, HWCAP2_I8MM}, {"bf16", hwcap2, HWCAP2_BF16},
        {"svei8mm", hwcap2, HWCAP2_SVEI8MM}, {"svebf16", hwcap2, HWCAP2_SVEBF16},
        {"sme", hwcap2, HWCAP2_SME}, {"sme2", hwcap2, HWCAP2_SME2},
        {"smei8i32", hwcap2, HWCAP2_SME_I8I32},
        {"smef16f32", hwcap2, HWCAP2_SME_F16F32},
        {"smeb16f32", hwcap2, HWCAP2_SME_B16F32},
        {"smef32f32", hwcap2, HWCAP2_SME_F32F32},
        {"smei16i32", hwcap2, HWCAP2_SME_I16I32},
        {"smebi32i32", hwcap2, HWCAP2_SME_BI32I32},
        {"aes", hwcap, HWCAP_AES}, {"pmull", hwcap, HWCAP_PMULL},
        {"sha1", hwcap, HWCAP_SHA1}, {"sha2", hwcap, HWCAP_SHA2},
        {"sha3", hwcap, HWCAP_SHA3}, {"sha512", hwcap, HWCAP_SHA512},
        {"crc32", hwcap, HWCAP_CRC32}
    };
    for (size_t i = 0; i < sizeof(features) / sizeof(features[0]); ++i)
        fprintf(stream, "%s\"%s\":%s", i ? "," : "", features[i].name,
            features[i].value & features[i].mask ? "true" : "false");
    fprintf(stream, "},\"sve\":{\"query_errno\":%d,\"vector_length_bytes\":", sve_errno);
    if (sve >= 0) fprintf(stream, "%d", sve & PR_SVE_VL_LEN_MASK);
    else fputs("null", stream);
    fprintf(stream, "},\"sme\":{\"query_errno\":%d,\"vector_length_bytes\":", sme_errno);
    if (sme >= 0) fprintf(stream, "%d", sme & PR_SME_VL_LEN_MASK);
    else fputs("null", stream);
    fputs("}}", stream);
    return finish_json(env, stream, &buffer);
}

static long long monotonic_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

// Fixed diagnostics only. Drain the PTY while reaping the child, with a 10s deadline.
JNIEXPORT jstring JNICALL Java_com_termux_terminal_PixelProbeProcess_collect(
        JNIEnv *env, jclass clazz, jint fd, jint pid) {
    (void) clazz;
    if (pid <= 1 || fd < 0)
        return (*env)->NewStringUTF(env, "{\"error\":\"Invalid subprocess handle\"}");
    unsigned char output[16384];
    size_t used = 0;
    int status = 0, wait_error = 0, read_error = 0;
    bool reaped = false, timed_out = false, truncated = false;
    long long deadline = monotonic_ms() + 10000;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        read_error = errno;
    }
    while (!reaped && !read_error) {
        struct pollfd poll_fd = {.fd = fd, .events = POLLIN};
        if (poll(&poll_fd, 1, 25) < 0 && errno != EINTR) {
            read_error = errno;
            break;
        }
        unsigned char chunk[1024];
        ssize_t count;
        // Bound draining too, so a noisy child cannot prevent the timeout check.
        for (int batch = 0; batch < 32; ++batch) {
            count = read(fd, chunk, sizeof(chunk));
            if (count <= 0) {
                if (count < 0 && errno != EAGAIN && errno != EIO && errno != EINTR)
                    read_error = errno;
                break;
            }
            for (ssize_t i = 0; i < count; ++i) {
                if (used < sizeof(output)) output[used++] = chunk[i];
                else truncated = true;
            }
        }
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) reaped = true;
        else if (result < 0 && errno != EINTR) {
            wait_error = errno;
            break;
        }
        if (!reaped && monotonic_ms() >= deadline) {
            timed_out = true;
            break;
        }
    }
    if (!reaped && !wait_error) {
        // setsid() in the existing JNI launcher makes the child its group leader.
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL); // Also covers a timeout before setsid().
        pid_t result;
        do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
        if (result == pid) reaped = true;
        else wait_error = errno;
    }
    // The child can write its final bytes between the last read and waitpid().
    if (reaped && !read_error) {
        unsigned char chunk[1024];
        for (int batch = 0; batch < 32; ++batch) {
            ssize_t count = read(fd, chunk, sizeof(chunk));
            if (count <= 0) {
                if (count < 0 && errno == EINTR) continue;
                if (count < 0 && errno != EAGAIN && errno != EIO) read_error = errno;
                break;
            }
            for (ssize_t i = 0; i < count; ++i) {
                if (used < sizeof(output)) output[used++] = chunk[i];
                else truncated = true;
            }
        }
    }
    char *buffer = NULL;
    size_t size = 0;
    FILE *stream = open_memstream(&buffer, &size);
    if (!stream) return (*env)->NewStringUTF(env, "{\"error\":\"open_memstream failed\"}");
    fprintf(stream, "{\"pid\":%d,\"timed_out\":%s,\"truncated\":%s,"
        "\"wait_errno\":%d,\"read_errno\":%d,\"exit_code\":", pid,
        timed_out ? "true" : "false", truncated ? "true" : "false", wait_error, read_error);
    if (reaped && WIFEXITED(status)) fprintf(stream, "%d", WEXITSTATUS(status));
    else fputs("null", stream);
    fprintf(stream, ",\"signal\":%d,\"output\":\"",
        reaped && WIFSIGNALED(status) ? WTERMSIG(status) : 0);
    for (size_t i = 0; i < used; ++i) {
        unsigned char c = output[i];
        if (c >= 32 && c < 127 && c != '"' && c != '\\') fputc(c, stream);
        else fprintf(stream, "\\u%04x", (unsigned int) c);
    }
    fputs("\"}", stream);
    return finish_json(env, stream, &buffer);
}
