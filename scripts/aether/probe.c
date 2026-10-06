#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <netdb.h>
#include <spawn.h>
#include <signal.h>
#include <pthread.h>
#include <sys/stat.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
static void check_identity(const char *path, const char *variable) {
    const char *expected_path=getenv(variable);assert(expected_path && *expected_path);
    char expected[256]={0},actual[256]={0};
    FILE *f=fopen(expected_path,"r");assert(f && fgets(expected,sizeof(expected),f));fclose(f);
    f=fopen(path,"r");assert(f && fgets(actual,sizeof(actual),f));fclose(f);
    assert(!strcmp(expected,actual));
    int fd=openat(AT_FDCWD,path,O_RDONLY);assert(fd>=0);
    memset(actual,0,sizeof(actual));assert(read(fd,actual,sizeof(actual)-1)>0);close(fd);
    assert(!strcmp(expected,actual));
    printf("%s=%s",variable,actual);
}
static void wait_code(pid_t pid, int expected) {
    int status; assert(waitpid(pid,&status,0)==pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status)==expected);
}
static void write_script(const char *path, const char *contents, mode_t mode) {
    int fd=open(path,O_WRONLY|O_CREAT|O_EXCL,mode); assert(fd>=0);
    size_t n=strlen(contents);assert(write(fd,contents,n)==(ssize_t)n);assert(!close(fd));
}
static void run_path(const char *path, int expected) {
    pid_t pid=fork();assert(pid>=0);
    if(!pid) { char *args[]={(char*)path,"argument with spaces",NULL};execve(path,args,environ);perror(path);_exit(125); }
    wait_code(pid,expected);
}
static void *system_thread(void *unused) {
    (void)unused; int status=system("exit 29");
    assert(WIFEXITED(status) && WEXITSTATUS(status)==29);return NULL;
}
static void *cancelled_system_thread(void *command) {
    int status=system(command);assert(status!=-1);return NULL;
}
static void check_execution(const char *self) {
    const char *tmp=getenv("TMPDIR");assert(tmp && *tmp);
    char directory[PATH_MAX];assert(snprintf(directory,sizeof(directory),"%s/aether-exec-XXXXXX",tmp)<(int)sizeof(directory));
    assert(mkdtemp(directory));
    char binary[PATH_MAX],script[PATH_MAX],envscript[PATH_MAX],raw[PATH_MAX],loop[PATH_MAX],noscript[PATH_MAX];
    assert(snprintf(binary,sizeof(binary),"%s/probe-child",directory)<(int)sizeof(binary));
    assert(snprintf(script,sizeof(script),"%s/script with spaces",directory)<(int)sizeof(script));
    assert(snprintf(envscript,sizeof(envscript),"%s/env-script",directory)<(int)sizeof(envscript));
    assert(snprintf(raw,sizeof(raw),"%s/raw-script",directory)<(int)sizeof(raw));
    assert(snprintf(loop,sizeof(loop),"%s/loop",directory)<(int)sizeof(loop));
    assert(snprintf(noscript,sizeof(noscript),"%s/not-executable",directory)<(int)sizeof(noscript));
    assert(!symlink(self,binary));
    char *old_path=getenv("PATH")?strdup(getenv("PATH")):NULL;
    char *path;assert(asprintf(&path,"%s:%s",directory,old_path?old_path:"/system/bin")>=0);
    assert(!setenv("PATH",path,1));free(path);assert(!setenv("AETHER_PROBE_FILE",self,1));
    char *args[]={"preserved argv zero","--child-identity",NULL};
    char *minimal[]={"PROBE_ENV=kept",NULL};
    for(int method=0;method<6;method++) {
        pid_t pid;
        if(method==2) { assert(!posix_spawnp(&pid,"probe-child",NULL,NULL,args,environ)); }
        else {
            pid=fork();assert(pid>=0);
            if(!pid) {
                switch(method) {
                    case 0:execvp("probe-child",args);break;
                    case 1:execvpe("probe-child",args,minimal);break;
                    case 3:execlp("probe-child",args[0],args[1],(char*)NULL);break;
                    case 4:execle(self,args[0],args[1],(char*)NULL,minimal);break;
                    case 5:execl(self,args[0],args[1],(char*)NULL);break;
                }
                perror("PATH/variadic exec");_exit(125);
            }
        }
        wait_code(pid,37);
    }
    puts("PATH, explicit environment, variadic exec and spawnp passed");
    write_script(script,"#!/bin/sh\ntest \"$1\" = \"argument with spaces\" || exit 91\n\"$AETHER_PROBE_FILE\" --child\n",0700);
    write_script(envscript,"#!/usr/bin/env sh\n\"$AETHER_PROBE_FILE\" --child\n",0700);
    write_script(raw,"exit 41\n",0700);write_script(noscript,"#!/bin/sh\nexit 0\n",0600);
    run_path(script,37);run_path(envscript,37);
    char *body;assert(asprintf(&body,"#!%s\n",loop)>=0);write_script(loop,body,0700);free(body);
    errno=0;assert(execve(loop,args,environ)==-1 && errno==ELOOP);
    errno=0;assert(execve(noscript,args,environ)==-1 && errno==EACCES);
    errno=0;assert(execve(raw,args,environ)==-1 && errno==ENOEXEC);
    pid_t pid=fork();assert(pid>=0);
    if(!pid) {execvp("raw-script",args);_exit(125);}wait_code(pid,41);
    assert(posix_spawnp(&pid,"raw-script",NULL,NULL,args,environ)==ENOEXEC);
    assert(posix_spawnp(&pid,"missing-aether-command",NULL,NULL,args,environ)==ENOENT);
    errno=EDOM;
    assert(posix_spawnp(&pid,"not-executable",NULL,NULL,args,environ)==EACCES && errno==EDOM);
    assert(!unlink(envscript));
    assert(asprintf(&body,"#!%s --script-child with spaces\n",self)>=0);
    write_script(envscript,body,0700);free(body);run_path(envscript,43);
    puts("Shell/env/glibc shebangs, mixed-libc children, recursion and permission errors passed");
    int status=system("\"$AETHER_PROBE_FILE\" --child");assert(WIFEXITED(status) && WEXITSTATUS(status)==37);
    assert(system(NULL));
    status=system("exit 23");assert(WIFEXITED(status) && WEXITSTATUS(status)==23);
    status=system("kill -TERM $$");assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGTERM);
    pthread_t threads[2];
    assert(!pthread_create(&threads[0],NULL,system_thread,NULL));
    assert(!pthread_create(&threads[1],NULL,system_thread,NULL));
    assert(!pthread_join(threads[0],NULL));assert(!pthread_join(threads[1],NULL));
    char marker[PATH_MAX];assert(snprintf(marker,sizeof(marker),"%s/system-child",directory)<(int)sizeof(marker));
    char *command;assert(asprintf(&command,"echo $$ > '%s'; exec sleep 30",marker)>=0);
    pthread_t cancel_thread;assert(!pthread_create(&cancel_thread,NULL,cancelled_system_thread,command));
    long child_pid=0;
    for(int i=0;i<100 && child_pid<=0;i++) {
        usleep(20000);FILE *child_file=fopen(marker,"r");
        if(child_file) {if(fscanf(child_file,"%ld",&child_pid)!=1) child_pid=0;fclose(child_file);}
    }
    assert(child_pid>0);
    assert(!pthread_cancel(cancel_thread));void *cancel_result;assert(!pthread_join(cancel_thread,&cancel_result));
    assert(cancel_result==PTHREAD_CANCELED);assert(kill((pid_t)child_pid,0)==-1 && errno==ESRCH);
    assert(!unlink(marker));free(command);
    puts("system shell, mixed-libc child, exit/signal status, concurrency and cancellation passed");
    assert(!unlink(binary));assert(!unlink(script));assert(!unlink(envscript));assert(!unlink(raw));assert(!unlink(loop));assert(!unlink(noscript));assert(!rmdir(directory));
    if(old_path) {setenv("PATH",old_path,1);free(old_path);}else unsetenv("PATH");
}
int main(int argc,char **argv) {
    setvbuf(stdout,NULL,_IONBF,0);
    if(argc>1 && !strcmp(argv[1],"--script-child with spaces")) {
        assert(argc==4 && !strcmp(argv[3],"argument with spaces"));return 43;
    }
    if(argc>1 && (!strcmp(argv[1],"--child") || !strcmp(argv[1],"--child-identity"))) {
        if(!strcmp(argv[1],"--child-identity")) assert(!strcmp(argv[0],"preserved argv zero"));
        if(getenv("PROBE_ENV")) {assert(!strcmp(getenv("PROBE_ENV"),"kept"));assert(!getenv("HOME"));}
        if(!strcmp(argv[0],"preserved argv zero")) assert(getenv("AETHER_TARGET"));
        return 37;
    }
    char path[4096]={0};ssize_t n=readlink("/proc/self/exe",path,sizeof(path)-1);assert(n>0);path[n]=0;
    printf("UID=%ld EXE=%s\n",(long)getuid(),path);
    assert(strstr(path,"aether-probe"));
    FILE *f=fopen("/etc/resolv.conf","r");assert(f);char line[256];assert(fgets(line,sizeof(line),f));fclose(f);puts("resolv.conf readable");
    check_identity("/sys/class/dmi/id/sys_vendor","AETHER_SYS_VENDOR_FILE");
    check_identity("/sys/class/dmi/id/product_name","AETHER_PRODUCT_NAME_FILE");
    puts("Android manufacturer/model mapping passed");
    struct addrinfo hints={0},*result=NULL;hints.ai_socktype=SOCK_STREAM;hints.ai_flags=AI_CANONNAME;
    int rc=getaddrinfo("browser.geekbench.com","443",&hints,&result);printf("DNS=%d\n",rc);assert(rc==0 && result);freeaddrinfo(result);
    hints.ai_flags=AI_NUMERICHOST;rc=getaddrinfo("not-a-numeric-address.invalid","443",&hints,&result);assert(rc==EAI_NONAME);
    rc=getaddrinfo("127.0.0.1","443",&hints,&result);assert(rc==0 && result);freeaddrinfo(result);
    puts("DNS success, numeric, and failure semantics passed");
    char *child[]={path,"--child",NULL};pid_t pid=fork();assert(pid>=0);
    if(!pid) { execve(path,child,environ);perror("execve");_exit(125); }
    int status;assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && WEXITSTATUS(status)==37);puts("execve child passed");
    rc=posix_spawn(&pid,path,NULL,NULL,child,environ);assert(!rc);
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && WEXITSTATUS(status)==37);puts("posix_spawn child passed");
    check_execution(path);
    puts("AETHER_PROBE_PASS");return 0;
}
