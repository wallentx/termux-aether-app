/* Execution interposition shared by the glibc runtime and its Bionic children.
 * This is compatibility plumbing, not a security boundary or a Linux rootfs. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define PREFIX "/data/data/com.termux/files/usr"
#define SHELL PREFIX "/bin/sh"
extern char **environ;

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
struct launch { char *path; char **args; char **env; };
static void release(struct launch *p) {
    int error = errno;
    free(p->path);
    if (p->args) { for (size_t i = 0; p->args[i]; ++i) free(p->args[i]); free(p->args); }
    if (p->env) { for (size_t i = 0; p->env[i]; ++i) free(p->env[i]); free(p->env); }
    memset(p, 0, sizeof(*p)); errno = error;
}
static size_t count(char *const values[]) {
    size_t n = 0; if (values) while (values[n]) ++n; return n;
}
static bool env_key(const char *entry, const char *key) {
    size_t n = strlen(key); return !strncmp(entry, key, n) && entry[n] == '=';
}
static const char *config(const char *key) {
    const char *v = getenv(key); return v && *v ? v : NULL;
}
static const char *const runtime_keys[] = {
    "AETHER_HELPER", "AETHER_LOADER", "AETHER_LIBRARIES", "AETHER_RUNTIME",
    "AETHER_GLIBC_PRELOAD", "AETHER_BIONIC_PRELOAD", "AETHER_BIONIC_LIBRARY_PATH",
    "AETHER_RESOLV_CONF", "AETHER_SYS_VENDOR_FILE", "AETHER_PRODUCT_NAME_FILE", NULL
};
static int child_env(struct launch *p, char *const env[], bool glibc, const char *target) {
    size_t n = count(env), j = 0;
    p->env = calloc(n + 20, sizeof(char *));
    if (!p->env) return -1;
    for (size_t i = 0; i < n; ++i) {
        bool skip = env_key(env[i], "LD_PRELOAD") || env_key(env[i], "LD_LIBRARY_PATH") ||
                    env_key(env[i], "AETHER_TARGET") || env_key(env[i], "TERMUX_EXEC__PROC_SELF_EXE");
        for (size_t k = 0; runtime_keys[k]; ++k) skip |= env_key(env[i], runtime_keys[k]);
        if (!skip && !(p->env[j++] = strdup(env[i]))) return -1;
    }
    for (size_t k = 0; runtime_keys[k]; ++k) {
        const char *v = config(runtime_keys[k]);
        if (v && asprintf(&p->env[j++], "%s=%s", runtime_keys[k], v) < 0) return -1;
    }
    const char *preload = config(glibc ? "AETHER_GLIBC_PRELOAD" : "AETHER_BIONIC_PRELOAD");
    const char *libs = config(glibc ? "AETHER_LIBRARIES" : "AETHER_BIONIC_LIBRARY_PATH");
    if (!preload) { errno = EINVAL; return -1; }
    if (asprintf(&p->env[j++], "LD_PRELOAD=%s", preload) < 0) return -1;
    if (libs && asprintf(&p->env[j++], "LD_LIBRARY_PATH=%s", libs) < 0) return -1;
    if (glibc && asprintf(&p->env[j++], "AETHER_TARGET=%s", target) < 0) return -1;
    return 0;
}
/* Only conventional shell/env locations are translated. Other interpreters must
 * exist at their stated paths; env-based shebangs can find them through PATH. */
static const char *interpreter_path(const char *path) {
    if (!strcmp(path, "/bin/sh")) return SHELL;
    if (!strcmp(path, "/bin/bash")) return PREFIX "/bin/bash";
    if (!strcmp(path, "/usr/bin/env")) return PREFIX "/bin/env";
    return path;
}
/* The original target must be executable even though a loader could read it.
 * Use the old faccessat syscall: faccessat2 may be blocked by Android seccomp. */
static int inspect(const char *path, char header[256], bool *glibc) {
    int fd = syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct stat st; int result = -1; *glibc = false;
    if (fstat(fd, &st)) goto done;
    if (!S_ISREG(st.st_mode) || !(st.st_mode & 0111)) { errno = EACCES; goto done; }
    if (syscall(SYS_faccessat, AT_FDCWD, path, X_OK, 0)) goto done;
    memset(header, 0, 256);
    ssize_t size = pread(fd, header, 256, 0);
    if (size < 0) goto done;
    if (size >= 2 && header[0] == '#' && header[1] == '!') { result = 1; goto done; }
    Elf64_Ehdr h;
    if (pread(fd, &h, sizeof(h), 0) != sizeof(h) || memcmp(h.e_ident, ELFMAG, SELFMAG)) {
        errno = ENOEXEC; goto done;
    }
    if (h.e_ident[EI_CLASS] != ELFCLASS64 || h.e_ident[EI_DATA] != ELFDATA2LSB ||
        h.e_machine != EM_AARCH64 || h.e_phentsize != sizeof(Elf64_Phdr) || h.e_phnum >= 128) {
        errno = ENOEXEC; goto done;
    }
    for (unsigned i = 0; i < h.e_phnum; ++i) {
        Elf64_Phdr ph;
        if (h.e_phoff > (uint64_t)st.st_size ||
            h.e_phoff + (uint64_t)(i + 1) * sizeof(ph) > (uint64_t)st.st_size ||
            pread(fd, &ph, sizeof(ph), h.e_phoff + i * sizeof(ph)) != sizeof(ph)) {
            errno = ENOEXEC; goto done;
        }
        if (ph.p_type == PT_INTERP) {
            char interp[PATH_MAX];
            if (!ph.p_filesz || ph.p_filesz > sizeof(interp) || ph.p_offset > (uint64_t)st.st_size ||
                ph.p_filesz > (uint64_t)st.st_size - ph.p_offset ||
                pread(fd, interp, ph.p_filesz, ph.p_offset) != (ssize_t)ph.p_filesz ||
                !memchr(interp, 0, ph.p_filesz)) { errno = ENOEXEC; goto done; }
            *glibc = !strcmp(interp, "/lib/ld-linux-aarch64.so.1") ||
                     !strcmp(interp, PREFIX "/glibc/lib/ld-linux-aarch64.so.1");
            if (!*glibc && strcmp(interp, "/system/bin/linker64")) { errno = ENOEXEC; goto done; }
            result = 0; goto done;
        }
    }
    /* Static binaries are passed through, without claiming compatibility. */
    result = 0;
done:
    { int error = errno; close(fd); errno = error; }
    return result;
}
static int prepare(const char *path, char *const argv[], char *const env[],
                   struct launch *out, bool spawning, unsigned depth) {
    if (depth > 4) { errno = ELOOP; return -1; }
    char header[256]; bool glibc;
    int kind = inspect(path, header, &glibc);
    if (kind < 0) return -1;
    size_t argc = count(argv);
    if (kind == 1) {
        /* Linux treats everything after the interpreter as ONE optional arg. */
        char *end = memchr(header, '\n', sizeof(header));
        if (!end) end = memchr(header, 0, sizeof(header));
        if (!end) { errno = ENOEXEC; return -1; }
        while (end > header + 2 && (end[-1] == ' ' || end[-1] == '\t')) --end;
        *end = 0;
        char *name = header + 2; while (*name == ' ' || *name == '\t') ++name;
        char *option = name; while (*option && *option != ' ' && *option != '\t') ++option;
        if (*option) { *option++ = 0; while (*option == ' ' || *option == '\t') ++option; }
        if (!*name) { errno = ENOEXEC; return -1; }
        char **args = calloc(argc + 4, sizeof(char *)); if (!args) return -1;
        size_t j = 0; args[j++] = (char *)interpreter_path(name);
        if (*option) args[j++] = option;
        args[j++] = (char *)path;
        for (size_t i = 1; i < argc; ++i) args[j++] = argv[i];
        int result = prepare(args[0], args, env, out, spawning, depth + 1);
        int error = errno; free(args); errno = error; return result;
    }
    /* Android's linker uses the invoked path as argv[0]. Resolving a Bionic
     * symlink here turns e.g. bin/env into bin/coreutils and loses its applet.
     * Keep that path; glibc has --argv0 and can use its canonical ELF identity. */
    char *target = NULL;
    if (glibc) target = realpath(path, NULL);
    else if (path[0] == '/') target = strdup(path);
    else {
        char *cwd = getcwd(NULL, 0); if (!cwd) return -1;
        int n = asprintf(&target, "%s/%s", cwd, path); free(cwd);
        if (n < 0) return -1;
    }
    if (!target) return -1;
    const char *loader = config("AETHER_LOADER"), *libs = config("AETHER_LIBRARIES");
    const char *helper = config("AETHER_HELPER");
    if (!loader || !libs || !helper) { free(target); errno = EINVAL; return -1; }
    bool bridge = !glibc;
#ifdef AETHER_BIONIC
    bridge = !glibc && spawning;
#else
    (void)spawning;
#endif
    out->path = strdup(glibc ? loader : bridge ? helper : target);
    out->args = calloc(argc + 8, sizeof(char *));
    if (!out->path || !out->args) { free(target); return -1; }
    const char *prefix[6]; size_t n = 0, j = 0;
    if (glibc) {
        prefix[n++] = loader; prefix[n++] = "--library-path"; prefix[n++] = libs;
        prefix[n++] = "--argv0"; prefix[n++] = argc ? argv[0] : ""; prefix[n++] = target;
    } else if (bridge) {
        prefix[n++] = helper; prefix[n++] = "--bionic"; prefix[n++] = target;
        prefix[n++] = argc ? argv[0] : "";
    } else prefix[n++] = argc ? argv[0] : "";
    for (size_t i = 0; i < n; ++i) if (!(out->args[j++] = strdup(prefix[i]))) { free(target); return -1; }
    for (size_t i = 1; i < argc; ++i) if (!(out->args[j++] = strdup(argv[i]))) { free(target); return -1; }
    int result = child_env(out, env, glibc, target); free(target); return result;
}
int execve(const char *path, char *const argv[], char *const env[]) {
    struct launch p = {0};
    if (prepare(path, argv, env, &p, false, 0)) { release(&p); return -1; }
#ifdef AETHER_BIONIC
    /* Preserve Termux's Android linker/W^X handling for Bionic executables. */
    int (*next)(const char *, char *const[], char *const[]) = dlsym(RTLD_NEXT, "execve");
    /* Never let termux-exec reinterpret the glibc loader invocation. */
    int result = !strcmp(p.path, config("AETHER_LOADER")) ?
        syscall(SYS_execve, p.path, p.args, p.env) : next(p.path, p.args, p.env);
#else
    int result = syscall(SYS_execve, p.path, p.args, p.env);
#endif
    release(&p); return result;
}
int execv(const char *path, char *const args[]) { return execve(path, args, environ); }
int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const args[], char *const env[]) {
    int saved = errno;
    struct launch p = {0};
    if (prepare(path, args, env, &p, true, 0)) { int error = errno; release(&p); errno = saved; return error; }
    spawn_fn next = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    int result = next(pid, p.path, actions, attr, p.args, p.env);
    release(&p); errno = saved; return result;
}
static int shell_fallback(const char *path, char *const args[], char *const env[]) {
    size_t n = count(args); char **shell = calloc(n + 3, sizeof(char *)); if (!shell) return -1;
    shell[0] = SHELL; shell[1] = (char *)path;
    for (size_t i = 1; i < n; ++i) shell[i + 1] = args[i];
    execve(SHELL, shell, env); int error = errno; free(shell); errno = error; return -1;
}
struct spawn_request { pid_t *pid; const posix_spawn_file_actions_t *actions; const posix_spawnattr_t *attr; };
static int attempt(const char *path, char *const args[], char *const env[], struct spawn_request *s) {
    if (s) return posix_spawn(s->pid, path, s->actions, s->attr, args, env);
    execve(path, args, env);
    if (errno == ENOEXEC) shell_fallback(path, args, env);
    return errno;
}
static int search(const char *file, char *const args[], char *const env[], struct spawn_request *s) {
    if (!*file) return ENOENT;
    if (strchr(file, '/')) return attempt(file, args, env, s);
    const char *path = getenv("PATH"); if (!path) path = PREFIX "/bin:/system/bin";
    bool denied = false;
    for (const char *start = path;;) {
        const char *end = strchr(start, ':'); size_t n = end ? (size_t)(end - start) : strlen(start);
        char *candidate = NULL;
        if (n >= PATH_MAX) { if (!end) break; start = end + 1; continue; }
        if (asprintf(&candidate, "%.*s%s%s", (int)n, start, n ? "/" : "", file) < 0) return ENOMEM;
        int error = attempt(candidate, args, env, s); free(candidate);
        if (!error) return 0;
        if (error == EACCES) denied = true;
        else if (error != ENOENT && error != ENOTDIR) return error;
        if (!end) break;
        start = end + 1;
    }
    return denied ? EACCES : ENOENT;
}
int execvpe(const char *file, char *const args[], char *const env[]) {
    errno = search(file, args, env, NULL); return -1;
}
int execvp(const char *file, char *const args[]) { return execvpe(file, args, environ); }
int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const args[], char *const env[]) {
    int saved = errno; struct spawn_request request = {pid, actions, attr};
    int result = search(file, args, env, &request); errno = saved; return result;
}
static int list_exec(const char *file, const char *first, va_list input, bool path, bool explicit_env) {
    size_t n = first ? 1 : 0; va_list copy; va_copy(copy, input);
    if (first) while (va_arg(copy, char *)) ++n;
    va_end(copy);
    char **args = calloc(n + 1, sizeof(char *)); if (!args) return -1;
    if (n) { args[0] = (char *)first; for (size_t i = 1; i < n; ++i) args[i] = va_arg(input, char *); (void)va_arg(input, char *); }
    char *const *env = explicit_env ? va_arg(input, char **) : environ;
    if (path) execvpe(file, args, env); else execve(file, args, env);
    int error = errno; free(args); errno = error; return -1;
}
int execl(const char *file, const char *first, ...) {
    va_list ap; va_start(ap, first); int r = list_exec(file, first, ap, false, false); va_end(ap); return r;
}
int execlp(const char *file, const char *first, ...) {
    va_list ap; va_start(ap, first); int r = list_exec(file, first, ap, true, false); va_end(ap); return r;
}
int execle(const char *file, const char *first, ...) {
    va_list ap; va_start(ap, first); int r = list_exec(file, first, ap, false, true); va_end(ap); return r;
}
