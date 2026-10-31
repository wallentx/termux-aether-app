/* system() for the glibc side: launch Termux's shell through the ABI dispatcher.
 * SIGINT/SIGQUIT dispositions are shared across concurrent calls; each caller
 * owns its signal mask and child. Deferred pthread cancellation reaps that child.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
static pthread_mutex_t system_mutex = PTHREAD_MUTEX_INITIALIZER;
static unsigned system_users;
static struct sigaction saved_int, saved_quit;
struct system_call { pid_t child; sigset_t mask; };
static void finish_system(void *ptr) {
    pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, NULL);
    struct system_call *call = ptr;
    if (call->child > 0) {
        kill(call->child, SIGKILL);
        while (waitpid(call->child, NULL, 0) < 0 && errno == EINTR) {}
    }
    pthread_mutex_lock(&system_mutex);
    if (!--system_users) {
        sigaction(SIGINT, &saved_int, NULL);
        sigaction(SIGQUIT, &saved_quit, NULL);
    }
    pthread_mutex_unlock(&system_mutex);
    pthread_sigmask(SIG_SETMASK, &call->mask, NULL);
}
int system(const char *command) {
    int cancel_state;
    pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, &cancel_state);
    struct sigaction ignore = {.sa_handler = SIG_IGN};
    sigemptyset(&ignore.sa_mask);
    sigset_t defaults, block;
    sigemptyset(&defaults); sigemptyset(&block); sigaddset(&block, SIGCHLD);
    pthread_mutex_lock(&system_mutex);
    if (!system_users++) {
        sigaction(SIGINT, &ignore, &saved_int);
        sigaction(SIGQUIT, &ignore, &saved_quit);
    }
    if (saved_int.sa_handler != SIG_IGN) sigaddset(&defaults, SIGINT);
    if (saved_quit.sa_handler != SIG_IGN) sigaddset(&defaults, SIGQUIT);
    pthread_mutex_unlock(&system_mutex);
    struct system_call call = {.child = -1};
    pthread_sigmask(SIG_BLOCK, &block, &call.mask);
    int status = -1, error = 0;
    pthread_cleanup_push(finish_system, &call);
    posix_spawnattr_t attr;
    error = posix_spawnattr_init(&attr);
    if (!error) {
        posix_spawnattr_setsigmask(&attr, &call.mask);
        posix_spawnattr_setsigdefault(&attr, &defaults);
        posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);
        char *args[] = {"sh", "-c", "--", (char *)(command ? command : "exit 0"), NULL};
        error = posix_spawn(&call.child, "/data/data/com.termux/files/usr/bin/sh", NULL, &attr, args, environ);
        if (error) call.child = -1;
        posix_spawnattr_destroy(&attr);
    }
    pthread_setcancelstate(cancel_state, NULL);
    if (!error) {
        pid_t waited;
        do { waited = waitpid(call.child, &status, 0); } while (waited < 0 && errno == EINTR);
        if (waited < 0) { error = errno; status = -1; }
        /* Reaped (or no longer our child): never signal a possibly reused PID. */
        call.child = -1;
    } else if (error != ENOMEM && error != EAGAIN) status = 127 << 8;
    pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, NULL);
    pthread_cleanup_pop(1);
    if (error) errno = error;
    pthread_setcancelstate(cancel_state, NULL);
    return command ? status : status == 0;
}
