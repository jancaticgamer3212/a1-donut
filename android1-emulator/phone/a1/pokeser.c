#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

/* The guest's init runs a root shell on the emulated serial port, which QEMU
 * exposes as a TCP server.  This sends lines to that shell so the host can
 * drive the guest without a working adb connection inside it. */

static int connect_to(int port, int timeout_ms) {
    struct sockaddr_in a;
    int tries = timeout_ms / 200;
    if (tries < 1) tries = 1;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = htonl(0x7f000001);
    for (int i = 0; i < tries; i++) {
        int s = socket(AF_INET, SOCK_STREAM, 0);
        if (s < 0) return -1;
        if (connect(s, (struct sockaddr *)&a, sizeof a) == 0) return s;
        close(s);
        usleep(200000);
    }
    return -1;
}

int main(int argc, char **argv) {
    int port = 5555, timeout_ms = 2000, quiet_ms = 600;
    int i = 1;
    for (; i < argc; i++) {
        if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) port = atoi(argv[++i]);
        else if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) timeout_ms = atoi(argv[++i]);
        else if (strcmp(argv[i], "-q") == 0 && i + 1 < argc) quiet_ms = atoi(argv[++i]);
        else break;
    }
    if (i >= argc) {
        fprintf(stderr, "usage: pokeser [-p port] [-t connect_ms] [-q quiet_ms] cmd...\n");
        return 2;
    }
    int s = connect_to(port, timeout_ms);
    if (s < 0) return 1;

    struct timeval tv = {0, 100000};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);

    char buf[8192];
    ssize_t n;
    /* Drain the console backlog so the guest never blocks on a full buffer. */
    while ((n = read(s, buf, sizeof buf)) > 0) {}

    for (; i < argc; i++) {
        char line[1024];
        int len = snprintf(line, sizeof line, "%s\n", argv[i]);
        if (write(s, line, len) != len) {
            close(s);
            return 1;
        }
        usleep(100000);
        while ((n = read(s, buf, sizeof buf)) > 0) {}
    }

    /* Keep draining for a short quiet period, then drop the connection. */
    for (;;) {
        n = read(s, buf, sizeof buf);
        if (n > 0) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            usleep(quiet_ms * 1000);
            close(s);
            return 0;
        }
        break;
    }
    close(s);
    return 0;
}
