#include <signal.h>
#include <unistd.h>
#include <stdio.h>

/* sush sends SIGHUP to its process group when it exits, and QEMU turns that
 * into exit(1).  Start the target in its own session so the hangup cannot
 * reach it. */
int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: spawn program [args...]\n");
        return 2;
    }
    pid_t p = fork();
    if (p < 0) {
        perror("fork");
        return 1;
    }
    if (p > 0) {
        return 0;
    }
    setsid();
    signal(SIGHUP, SIG_IGN);
    execv(argv[1], &argv[1]);
    perror("execv");
    return 1;
}
