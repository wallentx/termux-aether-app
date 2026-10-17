// Host regression test: compile in CI; all pidfd syscalls are fault-injected.
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifdef TEST_WITHOUT_PIDFD
#undef __NR_pidfd_open
#undef __NR_pidfd_send_signal
#endif

static long mock_syscall(long number, ...);
static pid_t unexpected_fork(void);
#define syscall mock_syscall
#define fork unexpected_fork
#include "../../app/src/main/cpp/aether-session.c"
#undef syscall
#undef fork

static int open_error, signal_error, open_calls, signal_calls, probe_fd = -1;
static char exception_message[256];

static long mock_syscall(long number, ...) {
    va_list args;
    va_start(args, number);
#if defined(__NR_pidfd_open) && defined(__NR_pidfd_send_signal)
    if (number == __NR_pidfd_open) {
        assert(va_arg(args, pid_t) == getpid());
        assert(va_arg(args, int) == 0);
        va_end(args);
        open_calls++;
        if (open_error) { errno = open_error; return -1; }
        probe_fd = open("/dev/null", O_RDONLY);
        assert(probe_fd >= 0);
        return probe_fd;
    }
    if (number == __NR_pidfd_send_signal) {
        assert(va_arg(args, int) == probe_fd);
        assert(va_arg(args, int) == 0);
        assert(va_arg(args, void *) == NULL);
        assert(va_arg(args, int) == 0);
        va_end(args);
        signal_calls++;
        if (signal_error) { errno = signal_error; return -1; }
        return 0;
    }
#endif
    va_end(args);
    assert(!"Unexpected syscall in capability check");
    return -1;
}

static pid_t unexpected_fork(void) {
    assert(!"Unsupported backend must not fork a command");
    return -1;
}

static jclass JNICALL find_class(JNIEnv *env, const char *name) {
    (void) env;
    assert(strcmp(name, "java/lang/IllegalStateException") == 0);
    return (jclass) &exception_message;
}

static jint JNICALL throw_new(JNIEnv *env, jclass type, const char *message) {
    (void) env; (void) type;
    snprintf(exception_message, sizeof(exception_message), "%s", message);
    return 0;
}

static const struct JNINativeInterface_ jni = {
    .FindClass = find_class,
    .ThrowNew = throw_new,
};

static void reset(int opening_error, int signaling_error) {
    open_error = opening_error;
    signal_error = signaling_error;
    open_calls = signal_calls = 0;
    probe_fd = -1;
    exception_message[0] = '\0';
}

static void check_probe_closed(void) {
    if (probe_fd >= 0) {
        assert(fcntl(probe_fd, F_GETFD) == -1);
        assert(errno == EBADF);
    }
}

static void check_rejected(int opening_error, int signaling_error, int expected_error) {
    JNIEnv env = &jni;
    for (int terminal = 0; terminal <= 1; terminal++) {
        reset(opening_error, signaling_error);
        // Missing JNI input handlers also prove rejection precedes any launch setup.
        assert(Java_com_termux_app_session_SessionNative_startUtf8(
            &env, NULL, NULL, NULL, NULL, 24, 80, 8, 16, (jboolean) terminal) == NULL);
        assert(strstr(exception_message, "pidfd_open and pidfd_send_signal"));
        assert(strstr(exception_message, strerror(expected_error)));
        check_probe_closed();
#ifdef TEST_WITHOUT_PIDFD
        assert(open_calls == 0 && signal_calls == 0);
#else
        assert(open_calls == 1 && signal_calls == (opening_error ? 0 : 1));
#endif
    }
}

int main(void) {
#ifdef TEST_WITHOUT_PIDFD
    check_rejected(0, 0, ENOSYS);
    puts("PASS: missing pidfd syscall definitions reject PTY and pipe launches");
#else
    JNIEnv env = &jni;
    reset(0, 0);
    assert(require_pidfd_support(&env) == 1);
    assert(open_calls == 1 && signal_calls == 1);
    assert(exception_message[0] == '\0');
    check_probe_closed();
    check_rejected(ENOSYS, 0, ENOSYS);
    check_rejected(EPERM, 0, EPERM);
    check_rejected(EMFILE, 0, EMFILE);
    check_rejected(0, ENOSYS, ENOSYS);
    check_rejected(0, EPERM, EPERM);
    puts("PASS: pidfd support accepted; open/signal failures reject PTY and pipe launches");
#endif
    return 0;
}
