#define _GNU_SOURCE
#include <arpa/inet.h>
#include <elf.h>
#include <errno.h>
#include <limits.h>
#include <netdb.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Simple bounded wire format shared with the glibc client; no libc structures cross ABIs. */
struct answer { int32_t family, socktype, protocol; uint32_t scope; uint16_t port; uint8_t addr[16]; char canon[256]; };
static int write_all(const void *data, size_t size) {
    const char *p=data;
    while(size) { ssize_t n=write(STDOUT_FILENO,p,size); if(n<0 && errno==EINTR) continue; if(n<=0) return -1; p+=n; size-=n; }
    return 0;
}
static int resolve(int argc, char **argv) {
    if(argc!=8) return 2;
    struct addrinfo hints={0}, *head=NULL;
    hints.ai_family=atoi(argv[4]); hints.ai_socktype=atoi(argv[5]); hints.ai_protocol=atoi(argv[6]);
    /* AI flag numbers differ between Bionic and glibc. Pass a small explicit bitset. */
    int flags=atoi(argv[7]);
    if(flags&1) hints.ai_flags|=AI_PASSIVE;
    if(flags&2) hints.ai_flags|=AI_CANONNAME;
    if(flags&4) hints.ai_flags|=AI_NUMERICHOST;
    if(flags&8) hints.ai_flags|=AI_NUMERICSERV;
    if(flags&16) hints.ai_flags|=AI_V4MAPPED;
    if(flags&32) hints.ai_flags|=AI_ALL;
    if(flags&64) hints.ai_flags|=AI_ADDRCONFIG;
    int result=getaddrinfo((flags&128)?NULL:argv[2],(flags&256)?NULL:argv[3],&hints,&head);
    int32_t status=result==0?0:result==EAI_NONAME?1:result==EAI_AGAIN?2:result==EAI_MEMORY?3:
        result==EAI_FAMILY?4:result==EAI_SERVICE?5:result==EAI_BADFLAGS?6:7;
    if(write_all(&status,sizeof(status))) return 1;
    if(result) return 0;
    for(struct addrinfo *p=head;p;p=p->ai_next) {
        struct answer a={0}; a.family=p->ai_family; a.socktype=p->ai_socktype; a.protocol=p->ai_protocol;
        if(p->ai_family==AF_INET) { struct sockaddr_in *s=(void*)p->ai_addr; a.port=s->sin_port; memcpy(a.addr,&s->sin_addr,4); }
        else if(p->ai_family==AF_INET6) { struct sockaddr_in6 *s=(void*)p->ai_addr; a.port=s->sin6_port; a.scope=s->sin6_scope_id; memcpy(a.addr,&s->sin6_addr,16); }
        else continue;
        if(p->ai_canonname) snprintf(a.canon,sizeof(a.canon),"%s",p->ai_canonname);
        if(write_all(&a,sizeof(a))) { freeaddrinfo(head); return 1; }
    }
    freeaddrinfo(head); return 0;
}
int main(int argc,char **argv) {
    extern char **environ;
    if(argc >= 4 && !strcmp(argv[1], "--bionic")) {
        execve(argv[2], argv + 3, environ);
        perror("aether Android child"); return 126;
    }
    if(argc>1 && !strcmp(argv[1],"--resolve")) return resolve(argc,argv);
    if(argc<2 || !strcmp(argv[1],"--help")) {
        puts("Usage: aether-run [--] PROGRAM [arguments...]\nExperimental glibc execution under the Termux app UID, without rish."); return argc<2?2:0;
    }
    int first=!strcmp(argv[1],"--")?2:1;
    if(first>=argc) return 2;
    char self[PATH_MAX], loader[PATH_MAX], preload[PATH_MAX], libraries[PATH_MAX];
    ssize_t n=readlink("/proc/self/exe",self,sizeof(self)-1);
    if(n<0 || n==(ssize_t)sizeof(self)-1) { perror("aether executable path"); return 1; } self[n]=0;
    char *slash=strrchr(self,'/'); if(!slash) return 1; *slash=0;
    const char *runtime=getenv("AETHER_RUNTIME");
    if(!runtime || !*runtime) { fputs("aether-run: reopen Termux to install the runtime\n",stderr); return 1; }
    snprintf(loader,sizeof(loader),"%s/libaether-loader.so",self);
    snprintf(preload,sizeof(preload),"%s/libaether-compat.so",runtime);
    snprintf(libraries,sizeof(libraries),"%s:/data/data/com.termux/files/usr/glibc/lib",runtime);
    char helper[PATH_MAX]; snprintf(helper,sizeof(helper),"%s/libaether-run.so",self);
    char *bionic_preload=NULL;
    const char *inherited=getenv("LD_PRELOAD");
    if(asprintf(&bionic_preload,"%s/libaether-exec.so%s%s",self,
                inherited && *inherited ? ":" : "", inherited ? inherited : "")<0) return 1;
    const char *bionic_libraries=getenv("LD_LIBRARY_PATH");
    if(bionic_libraries) setenv("AETHER_BIONIC_LIBRARY_PATH",bionic_libraries,1);
    else unsetenv("AETHER_BIONIC_LIBRARY_PATH");
    setenv("AETHER_BIONIC_PRELOAD",bionic_preload,1); free(bionic_preload);
    setenv("AETHER_GLIBC_PRELOAD",preload,1);
    setenv("AETHER_HELPER",helper,1); setenv("AETHER_LOADER",loader,1);
    setenv("AETHER_LIBRARIES",libraries,1); setenv("LD_PRELOAD",preload,1); setenv("LD_LIBRARY_PATH",libraries,1);
    setenv("SSL_CERT_FILE","/data/data/com.termux/files/usr/etc/tls/cert.pem",1);
    setenv("CURL_CA_BUNDLE","/data/data/com.termux/files/usr/etc/tls/cert.pem",1);
    execvp(argv[first], argv + first);
    int error=errno; perror("aether-run"); return error == ENOENT ? 127 : 126;
}
