#define _GNU_SOURCE
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

// The native monitor owns the command child and reserves its PID until finish().
// Its socket peer lives only in the UserService: EOF triggers run-as cleanup even
// when that service is killed before it can return a handle to the application.
struct session_watch {
    pid_t pid, monitor;
    int socket, status;
    struct session_watch *next;
};
static pthread_mutex_t watches_lock = PTHREAD_MUTEX_INITIALIZER;
static struct session_watch *watches;
static char *cleanup_directory, *cleanup_class_path;

static void fail(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), message);
}

static char *copy_string(JNIEnv *env, jbyteArray text) {
    if (!text) return NULL;
    jsize length = (*env)->GetArrayLength(env, text);
    char *copy = malloc((size_t) length + 1);
    if (!copy) return NULL;
    (*env)->GetByteArrayRegion(env, text, 0, length, (jbyte *) copy);
    copy[length] = '\0';
    return copy;
}

JNIEXPORT void JNICALL Java_com_termux_app_session_SessionNative_configureCleanup(
        JNIEnv *env, jclass type, jbyteArray directory, jbyteArray class_path) {
    (void) type;
    char *dir = copy_string(env, directory), *path = copy_string(env, class_path);
    if (!dir || !path) {
        free(dir); free(path);
        if (!(*env)->ExceptionCheck(env)) fail(env, "Cannot configure session cleanup");
        return;
    }
    free(cleanup_directory); free(cleanup_class_path);
    cleanup_directory = dir; cleanup_class_path = path;
}

static void close_except(int keep, long max_fd) {
#if defined(__NR_close_range)
    if (keep < 3) {
        if (syscall(__NR_close_range, 3U, ~0U, 0) == 0) return;
    } else {
        int before = keep == 3 ? 0 : (int) syscall(__NR_close_range, 3U, (unsigned int) keep - 1, 0);
        int after = (int) syscall(__NR_close_range, (unsigned int) keep + 1, ~0U, 0);
        if (before == 0 && after == 0) return;
    }
#endif
    for (int fd = 3; fd < max_fd; ++fd) if (fd != keep) close(fd);
}

static int read_int(int fd, int *value) {
    size_t offset = 0;
    while (offset < sizeof(*value)) {
        ssize_t count = read(fd, (char *) value + offset, sizeof(*value) - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        offset += (size_t) count;
    }
    return 0;
}

static int send_int(int fd, int value) {
    size_t offset = 0;
    while (offset < sizeof(value)) {
        ssize_t count = send(fd, (char *) &value + offset, sizeof(value) - offset, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        offset += (size_t) count;
    }
    return 0;
}

static void cleanup_child(pid_t pid, char *package, char *class_path_option, char **environment, long max_fd) {
    // Run cleanup after the UID change; a signal set before run-as would be reset.
    char number[32];
    unsigned int value = (unsigned int) pid;
    char *end = number + sizeof(number) - 1;
    *end = '\0';
    do { *--end = (char) ('0' + value % 10); value /= 10; } while (value);
    char *argv[] = {"run-as", package, "/system/bin/app_process", class_path_option,
        "/system/bin", "com.termux.app.session.SessionSignals", cleanup_directory, end, "stop", NULL};
    pid_t helper = fork();
    if (helper == 0) {
        int null_fd = open("/dev/null", O_RDWR);
        if (null_fd < 0 || dup2(null_fd, 0) < 0 || dup2(null_fd, 1) < 0 || dup2(null_fd, 2) < 0) _exit(126);
        close_except(-1, max_fd);
        execve("/system/bin/run-as", argv, environment);
        _exit(126);
    }
    if (helper > 0) while (waitpid(helper, NULL, 0) < 0 && errno == EINTR) {}
    // Also covers a service crash before the command has dropped the shell UID.
    kill(pid, SIGKILL);
}

static void monitor_child(pid_t pid, int control, char *package, char *class_path_option,
                          char **environment, long max_fd) {
    close_except(control, max_fd);
    close(0); close(1); close(2);
    int pidfd = -1;
#if defined(__NR_pidfd_open)
    pidfd = (int) syscall(__NR_pidfd_open, pid, 0);
#endif
    int exited = 0;
    if (send_int(control, pid) < 0) goto lost;
    for (;;) {
        struct pollfd ready[] = {{control, POLLIN, 0}, {exited ? -1 : pidfd, POLLIN, 0}};
        // Older kernels without pidfds use a bounded waitid check instead.
        int result = poll(ready, 2, exited || pidfd >= 0 ? -1 : 100);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) goto lost;
        if (ready[0].revents) {
            char ack;
            ssize_t count;
            do { count = read(control, &ack, 1); } while (count < 0 && errno == EINTR);
            if (count == 1 && ack == 'F' && exited) break;
            goto lost;
        }
        if (!exited) {
            siginfo_t info = {0};
            if (waitid(P_PID, (id_t) pid, &info, WEXITED | WNOWAIT | WNOHANG) < 0) {
                if (errno == EINTR) continue;
                goto lost;
            }
            if (info.si_pid == pid) {
                exited = 1;
                int status = info.si_code == CLD_EXITED ? info.si_status : -info.si_status;
                if (send_int(control, status) < 0) goto lost;
            }
        }
    }
    goto reap;
lost:
    cleanup_child(pid, package, class_path_option, environment, max_fd);
reap:
    while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
    if (pidfd >= 0) close(pidfd);
    close(control);
    _exit(0);
}

static struct session_watch *find_watch(pid_t pid) {
    pthread_mutex_lock(&watches_lock);
    struct session_watch *watch = watches;
    while (watch && watch->pid != pid) watch = watch->next;
    pthread_mutex_unlock(&watches_lock);
    return watch;
}

JNIEXPORT jintArray JNICALL Java_com_termux_app_session_SessionNative_startUtf8(
        JNIEnv *env, jclass type, jbyteArray package, jbyteArray script, jobjectArray environment,
        jint rows, jint cols, jint cell_width, jint cell_height, jboolean terminal) {
    (void) type;
    char *pkg = copy_string(env, package), *command = copy_string(env, script);
    jsize count = (*env)->GetArrayLength(env, environment);
    char **envp = calloc((size_t) count + 1, sizeof(char *));
    int master = -1, slave = -1;
    int control[2] = {-1, -1};
    int pipes[3][2] = {{-1, -1}, {-1, -1}, {-1, -1}};
    struct session_watch *watch = calloc(1, sizeof(*watch));
    char *class_path_option = NULL;
    jintArray result = NULL;
    if (!pkg || !command || !envp || !watch || !cleanup_directory || !cleanup_class_path) goto error;
    if (asprintf(&class_path_option, "-Djava.class.path=%s", cleanup_class_path) < 0) goto error;
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, control) < 0) goto error;
    for (int i = 0; i < 2; i++) if (control[i] < 3) {
        int fd = fcntl(control[i], F_DUPFD_CLOEXEC, 3);
        if (fd < 0) goto error;
        close(control[i]); control[i] = fd;
    }
    for (jsize i = 0; i < count; ++i) {
        jbyteArray value = (*env)->GetObjectArrayElement(env, environment, i);
        envp[i] = copy_string(env, value);
        (*env)->DeleteLocalRef(env, value);
        if (!envp[i]) goto error;
    }
    if (terminal) {
        master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
        char name[128];
        if (master < 0 || grantpt(master) || unlockpt(master) || ptsname_r(master, name, sizeof(name))) goto error;
        slave = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (slave < 0) goto error;
        struct termios term;
        if (tcgetattr(slave, &term)) goto error;
        term.c_iflag |= IUTF8;
        term.c_iflag &= ~(IXON | IXOFF);
        if (tcsetattr(slave, TCSANOW, &term)) goto error;
        struct winsize size = {0};
        size.ws_row = (unsigned short) rows;
        size.ws_col = (unsigned short) cols;
        size.ws_xpixel = (unsigned short) (cols * cell_width);
        size.ws_ypixel = (unsigned short) (rows * cell_height);
        if (ioctl(slave, TIOCSWINSZ, &size)) goto error;
    } else {
        for (int i = 0; i < 3; i++) {
            if (pipe2(pipes[i], O_CLOEXEC)) goto error;
            for (int j = 0; j < 2; j++) {
                // Keep all pipe ends above stdio so dup2 cannot clobber a later source.
                if (pipes[i][j] < 3) {
                    int fd = fcntl(pipes[i][j], F_DUPFD_CLOEXEC, 3);
                    if (fd < 0) goto error;
                    close(pipes[i][j]);
                    pipes[i][j] = fd;
                }
            }
        }
    }
    // Allocate before fork so an allocation failure cannot abandon an unreported shell.
    result = (*env)->NewIntArray(env, terminal ? 2 : 4);
    if (!result) goto error;
    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 0) max_fd = 65536;
    char *argv[] = {"run-as", pkg, "/system/bin/sh", "-c", command, NULL};
    pid_t monitor = fork();
    if (monitor < 0) goto error;
    if (monitor == 0) {
        signal(SIGCHLD, SIG_DFL);
        pid_t pid = fork();
        if (pid < 0) { send_int(control[1], -1); _exit(126); }
        if (pid > 0) monitor_child(pid, control[1], pkg, class_path_option, envp, max_fd);
        sigset_t signals;
        sigfillset(&signals);
        sigprocmask(SIG_UNBLOCK, &signals, NULL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        if (setsid() < 0) _exit(126);
        if (terminal) {
            if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
            if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) _exit(126);
        } else {
            if (dup2(pipes[0][0], 0) < 0 || dup2(pipes[1][1], 1) < 0 || dup2(pipes[2][1], 2) < 0) _exit(126);
        }
        close_except(-1, max_fd);
        execve("/system/bin/run-as", argv, envp);
        static const char message[] = "Cannot execute Android run-as.\r\n";
        write(2, message, sizeof(message) - 1);
        _exit(126);
    }
    close(control[1]); control[1] = -1;
    int pid;
    if (read_int(control[0], &pid) < 0 || pid <= 0) {
        close(control[0]); control[0] = -1;
        while (waitpid(monitor, NULL, 0) < 0 && errno == EINTR) {}
        goto error;
    }
    watch->pid = pid; watch->monitor = monitor; watch->socket = control[0]; watch->status = 127;
    pthread_mutex_lock(&watches_lock);
    watch->next = watches;
    watches = watch;
    pthread_mutex_unlock(&watches_lock);
    watch = NULL;
    if (terminal) {
        jint values[] = {(jint) pid, master};
        (*env)->SetIntArrayRegion(env, result, 0, 2, values);
        close(slave);
    } else {
        jint values[] = {(jint) pid, pipes[0][1], pipes[1][0], pipes[2][0]};
        (*env)->SetIntArrayRegion(env, result, 0, 4, values);
        close(pipes[0][0]); close(pipes[1][1]); close(pipes[2][1]);
    }
    for (jsize i = 0; i < count; ++i) free(envp[i]);
    free(envp); free(pkg); free(command); free(class_path_option);
    return result;
error:
    if (master >= 0) close(master);
    if (slave >= 0) close(slave);
    for (int i = 0; i < 2; i++) if (control[i] >= 0) close(control[i]);
    for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++)
        if (pipes[i][j] >= 0) close(pipes[i][j]);
    if (envp) { for (jsize i = 0; i < count; ++i) free(envp[i]); free(envp); }
    free(pkg); free(command); free(class_path_option); free(watch);
    if (!(*env)->ExceptionCheck(env)) fail(env, terminal ? "Cannot create session PTY" : "Cannot create background pipes");
    return NULL;
}

// The session leader remains unreaped while inspecting its session, preventing PID/SID reuse.
static void stop_members(pid_t leader, int signal_number) {
    DIR *proc = opendir("/proc");
    if (!proc) return;
    struct dirent *entry;
    while ((entry = readdir(proc))) {
        char *end;
        long value = strtol(entry->d_name, &end, 10);
        if (*end || value < 1 || value > INT_MAX || value == leader) continue;
        pid_t pid = (pid_t) value;
        // Pin the process identity before inspecting it. Never signal a recycled numeric PID.
#if defined(__NR_pidfd_open) && defined(__NR_pidfd_send_signal)
        int pidfd = (int) syscall(__NR_pidfd_open, pid, 0);
        if (pidfd >= 0) {
            if (getsid(pid) == leader) syscall(__NR_pidfd_send_signal, pidfd, signal_number, NULL, 0);
            close(pidfd);
        }
#else
        (void) pid;
        (void) signal_number;
#endif
    }
    closedir(proc);
}

JNIEXPORT void JNICALL Java_com_termux_app_session_SessionNative_awaitExit(JNIEnv *env, jclass type, jint pid) {
    (void) env; (void) type;
    struct session_watch *watch = find_watch(pid);
    if (watch) read_int(watch->socket, &watch->status);
}

JNIEXPORT jint JNICALL Java_com_termux_app_session_SessionNative_finish(JNIEnv *env, jclass type, jint pid) {
    (void) env; (void) type;
    pthread_mutex_lock(&watches_lock);
    struct session_watch **entry = &watches;
    while (*entry && (*entry)->pid != pid) entry = &(*entry)->next;
    struct session_watch *watch = *entry;
    if (watch) *entry = watch->next;
    pthread_mutex_unlock(&watches_lock);
    if (!watch) return 127;
    int status = watch->status;
    char ack = 'F';
    send(watch->socket, &ack, 1, MSG_NOSIGNAL);
    close(watch->socket);
    pid_t result;
    do { result = waitpid(watch->monitor, NULL, 0); } while (result < 0 && errno == EINTR);
    free(watch);
    if (result < 0) return 127;
    return status;
}

JNIEXPORT jint JNICALL Java_com_termux_app_session_SessionNative_signal(JNIEnv *env, jclass type, jint pid, jboolean terminate) {
    (void) env; (void) type;
    stop_members(pid, terminate ? SIGKILL : SIGHUP);
    // The owning service keeps the leader unreaped until this helper returns.
    if (terminate && kill(pid, SIGKILL) < 0) return errno;
    return 0;
}
