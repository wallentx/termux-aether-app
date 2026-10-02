#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <spawn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
struct answer { int32_t family, socktype, protocol; uint32_t scope; uint16_t port; uint8_t addr[16]; char canon[256]; };
static ssize_t read_all(int fd,void *data,size_t size) {
    size_t done=0; while(done<size) { ssize_t n=read(fd,(char*)data+done,size-done); if(n<0 && errno==EINTR) continue; if(n<0) return -1; if(!n) break; done+=(size_t)n; } return (ssize_t)done;
}
static const char *map_path(const char *path) {
    if(!path) return path;
    if(!strcmp(path,"/etc/ssl/certs/ca-certificates.crt") || !strcmp(path,"/etc/ssl/cert.pem")) {
        const char *cert=getenv("SSL_CERT_FILE"); if(cert && *cert) return cert;
    }
    if(!strcmp(path,"/etc/resolv.conf")) {
        const char *resolv=getenv("AETHER_RESOLV_CONF"); if(resolv && *resolv) return resolv;
    }
    if(!strcmp(path,"/sys/class/dmi/id/sys_vendor")) {
        const char *vendor=getenv("AETHER_SYS_VENDOR_FILE"); if(vendor && *vendor) return vendor;
    }
    if(!strcmp(path,"/sys/class/dmi/id/product_name")) {
        const char *model=getenv("AETHER_PRODUCT_NAME_FILE"); if(model && *model) return model;
    }
    return path;
}
FILE *fopen(const char *path,const char *mode) { FILE *(*fn)(const char*,const char*)=dlsym(RTLD_NEXT,"fopen"); return fn(map_path(path),mode); }
FILE *fopen64(const char *path,const char *mode) { FILE *(*fn)(const char*,const char*)=dlsym(RTLD_NEXT,"fopen64"); return fn(map_path(path),mode); }
#include <stdarg.h>
static mode_t file_mode(int flags, va_list args) { return (flags&O_CREAT) || ((flags&O_TMPFILE)==O_TMPFILE) ? (mode_t)va_arg(args,int):0; }
int open(const char *p,int flags,...) { va_list a;va_start(a,flags);mode_t m=file_mode(flags,a);va_end(a);return syscall(SYS_openat,AT_FDCWD,map_path(p),flags,m); }
int open64(const char *p,int flags,...) { va_list a;va_start(a,flags);mode_t m=file_mode(flags,a);va_end(a);return syscall(SYS_openat,AT_FDCWD,map_path(p),flags,m); }
int openat(int fd,const char *p,int flags,...) { va_list a;va_start(a,flags);mode_t m=file_mode(flags,a);va_end(a);return syscall(SYS_openat,fd,map_path(p),flags,m); }
int openat64(int fd,const char *p,int flags,...) { va_list a;va_start(a,flags);mode_t m=file_mode(flags,a);va_end(a);return syscall(SYS_openat,fd,map_path(p),flags,m); }
ssize_t readlink(const char *path,char *buf,size_t size) {
    char self[64];snprintf(self,sizeof(self),"/proc/%ld/exe",(long)getpid());
    const char *target=getenv("AETHER_TARGET");
    if(target && (!strcmp(path,"/proc/self/exe") || !strcmp(path,self))) {
        if(!size) { errno=EINVAL;return -1; } size_t n=strlen(target);if(n>size)n=size;memcpy(buf,target,n);return (ssize_t)n;
    }
    return syscall(SYS_readlinkat,AT_FDCWD,path,buf,size);
}
ssize_t readlinkat(int fd,const char *path,char *buf,size_t size) {
    if(path && path[0]=='/') return readlink(path,buf,size);
    return syscall(SYS_readlinkat,fd,path,buf,size);
}

/* Bionic resolves names in a separate process; its libc never enters this process. */
int getaddrinfo(const char *node,const char *service,const struct addrinfo *hints,struct addrinfo **result) {
    if(!result) return EAI_FAIL;
    *result=NULL;
    const char *helper=getenv("AETHER_HELPER");
    if(!helper || !*helper) return EAI_FAIL;
    struct addrinfo zero={0}; if(!hints) { zero.ai_flags=AI_V4MAPPED|AI_ADDRCONFIG; hints=&zero; }
    int known=AI_PASSIVE|AI_CANONNAME|AI_NUMERICHOST|AI_NUMERICSERV|AI_V4MAPPED|AI_ALL|AI_ADDRCONFIG;
    if(hints->ai_flags & ~known) return EAI_BADFLAGS;
    int bits=((hints->ai_flags&AI_PASSIVE)?1:0)|((hints->ai_flags&AI_CANONNAME)?2:0)|
        ((hints->ai_flags&AI_NUMERICHOST)?4:0)|((hints->ai_flags&AI_NUMERICSERV)?8:0)|
        ((hints->ai_flags&AI_V4MAPPED)?16:0)|((hints->ai_flags&AI_ALL)?32:0)|((hints->ai_flags&AI_ADDRCONFIG)?64:0)|(!node?128:0)|(!service?256:0);
    char family[16],socktype[16],protocol[16],flags[16];
    snprintf(family,sizeof(family),"%d",hints->ai_family);snprintf(socktype,sizeof(socktype),"%d",hints->ai_socktype);
    snprintf(protocol,sizeof(protocol),"%d",hints->ai_protocol);snprintf(flags,sizeof(flags),"%d",bits);
    char *args[]={(char*)helper,"--resolve",(char*)(node?node:""),(char*)(service?service:""),family,socktype,protocol,flags,NULL};
    int pipefd[2];if(pipe2(pipefd,O_CLOEXEC)) return EAI_SYSTEM;
    posix_spawn_file_actions_t actions;posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_adddup2(&actions,pipefd[1],STDOUT_FILENO);
    posix_spawn_file_actions_addclose(&actions,pipefd[0]);posix_spawn_file_actions_addclose(&actions,pipefd[1]);
    /* Minimal environment intentionally prevents a glibc preload from entering Bionic. */
    char *env[]={"PATH=/system/bin","LANG=C",NULL};
    int (*spawn)(pid_t*,const char*,const posix_spawn_file_actions_t*,const posix_spawnattr_t*,char *const[],char *const[])=dlsym(RTLD_NEXT,"posix_spawn");
    pid_t pid;int err=spawn(&pid,helper,&actions,NULL,args,env);
    posix_spawn_file_actions_destroy(&actions);close(pipefd[1]);
    if(err) { close(pipefd[0]);errno=err;return EAI_SYSTEM; }
    int32_t status=7;ssize_t count=read_all(pipefd[0],&status,sizeof(status));
    int code=EAI_FAIL;
    const int codes[]={0,EAI_NONAME,EAI_AGAIN,EAI_MEMORY,EAI_FAMILY,EAI_SERVICE,EAI_BADFLAGS,EAI_FAIL};
    if(count==(ssize_t)sizeof(status) && status>=0 && status<8) code=codes[status];
    struct addrinfo **tail=result;
    if(code==0) {
        struct answer a;
        while((count=read_all(pipefd[0],&a,sizeof(a)))>0) {
            if(count!=(ssize_t)sizeof(a) || (a.family!=AF_INET && a.family!=AF_INET6)) { code=EAI_FAIL;break; }
            /* glibc freeaddrinfo frees each node and its canonname; sockaddr lives in-node. */
            struct addrinfo *item=calloc(1,sizeof(*item)+sizeof(struct sockaddr_storage));
            if(!item) { code=EAI_MEMORY;break; }
            item->ai_family=a.family;item->ai_socktype=a.socktype;item->ai_protocol=a.protocol;item->ai_flags=hints->ai_flags;
            item->ai_addr=(void*)(item+1);
            if(a.family==AF_INET) { struct sockaddr_in *s=(void*)item->ai_addr;s->sin_family=AF_INET;s->sin_port=a.port;memcpy(&s->sin_addr,a.addr,4);item->ai_addrlen=sizeof(*s); }
            else { struct sockaddr_in6 *s=(void*)item->ai_addr;s->sin6_family=AF_INET6;s->sin6_port=a.port;s->sin6_scope_id=a.scope;memcpy(&s->sin6_addr,a.addr,16);item->ai_addrlen=sizeof(*s); }
            a.canon[255]=0;
            if(a.canon[0] && !*result) { item->ai_canonname=strdup(a.canon);if(!item->ai_canonname){free(item);code=EAI_MEMORY;break;} }
            *tail=item;tail=&item->ai_next;
        }
        if(count<0 || !*result) code=EAI_FAIL;
    }
    close(pipefd[0]);int waitstatus;
    while(waitpid(pid,&waitstatus,0)<0) { if(errno!=EINTR) { code=EAI_SYSTEM;break; } }
    if(code && *result) { freeaddrinfo(*result);*result=NULL; }
    return code;
}
