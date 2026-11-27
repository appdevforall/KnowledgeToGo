/*
 * probe.c: raw system calls for the kernel matrix (K2GO-595).
 * Author    : AppDevForAll
 * Copyright : Copyright (c) 2026 AppDevForAll
 *
 * Calls one system call by number, so the test sees what the kernel (or proot) does with it, with
 * no libc fallback in between. Exit 0 = the call worked, 38 = ENOSYS (the kernel does not have it),
 * 1 = another error, 2 = usage.
 *   probe futex_time64          syscall 422 (32-bit ARM only), FUTEX_WAKE on a local word
 *   probe fchmodat2 <path>      syscall 452, mode 0640
 *   probe clock_gettime64       syscall 403 (32-bit ARM only) as glibc calls it: on ENOSYS, fall
 *                               back to clock_gettime. Exit 0 only for a real time.
 *   probe futex_time64-timeout  syscall 422 FUTEX_WAIT, 3 times with a 0.9 s timeout; exit 0 only
 *                               when the 3 waits take 2 to 5 s (a wrong timespec waits 0 s)
 *   probe seccomp-trap <n,n> <command...>
 *                               install a filter that traps (SIGSYS) these 32-bit ARM syscalls,
 *                               as the Android app filter does, then run the command in its place
 */
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

#define NR_FUTEX_TIME64 422 /* 32-bit ABIs only; Linux 5.1 */
#define NR_FCHMODAT2 452    /* Linux 6.6 */
#define NR_CLOCK_GETTIME64 403 /* 32-bit ABIs only; Linux 5.1 */
#define NR_CLOCK_GETTIME_ARM 263
#define FUTEX_WAKE_OP_CODE 1
#define MAX_TRAPS 8

/* Trap the listed syscall numbers of the 32-bit ARM ABI; allow all else. The filter stays on the
 * command and its children (proot and its tracees), as the app filter does on Android. */
static int seccomp_trap(const char* list, char** cmd) {
  struct sock_filter f[5 + 2 * MAX_TRAPS];
  unsigned short n = 0;
  long nr[MAX_TRAPS];
  int count = 0;
  char* copy = strdup(list);
  for (char* t = strtok(copy, ","); t != NULL && count < MAX_TRAPS; t = strtok(NULL, ",")) {
    nr[count++] = strtol(t, NULL, 10);
  }
  free(copy);
  f[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
  f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_ARM, 1, 0);
  f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  f[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
  for (int i = 0; i < count; i++) {
    f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)nr[i], 0, 1);
    f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
  }
  f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  struct sock_fprog prog = {n, f};
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 ||
      prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog, 0, 0) != 0) {
    printf("seccomp filter: errno=%d (%s)\n", errno, strerror(errno));
    return 1;
  }
  execvp(cmd[0], cmd);
  printf("exec %s: errno=%d (%s)\n", cmd[0], errno, strerror(errno));
  return 1;
}

static int result(long r) {
  if (r >= 0) return 0;
  printf("errno=%d (%s)\n", errno, strerror(errno));
  return errno == ENOSYS ? 38 : 1;
}

int main(int argc, char** argv) {
  if (argc == 2 && strcmp(argv[1], "futex_time64") == 0) {
#if defined(__arm__)
    int word = 0;
    return result(syscall(NR_FUTEX_TIME64, &word, FUTEX_WAKE_OP_CODE, 1, NULL, NULL, 0));
#else
    printf("futex_time64 exists only on 32-bit ABIs\n");
    return 2;
#endif
  }
  if (argc == 3 && strcmp(argv[1], "fchmodat2") == 0) {
    return result(syscall(NR_FCHMODAT2, AT_FDCWD, argv[2], 0640, 0));
  }
  if (argc == 2 && strcmp(argv[1], "clock_gettime64") == 0) {
#if defined(__arm__)
    /* proot emulates getcwd and voids it, so the trapped avoider syscall comes first, as in node. */
    char cwd[256];
    if (getcwd(cwd, sizeof(cwd)) == NULL) return result(-1);
    struct { int64_t sec; int64_t nsec; } ts64 = {0, 0};
    long r = syscall(NR_CLOCK_GETTIME64, CLOCK_MONOTONIC, &ts64);
    if (r == -1 && errno == ENOSYS) {
      struct timespec ts = {0, 0};
      if (syscall(NR_CLOCK_GETTIME_ARM, CLOCK_MONOTONIC, &ts) != 0) return result(-1);
      ts64.sec = ts.tv_sec;
      ts64.nsec = ts.tv_nsec;
    } else if (r != 0) {
      printf("clock_gettime64 returned %ld, not 0 or ENOSYS (a swallowed SIGSYS)\n", r);
      return 1;
    }
    if (ts64.sec == 0 && ts64.nsec == 0) {
      printf("no time: monotonic clock is 0\n");
      return 1;
    }
    return 0;
#else
    printf("clock_gettime64 exists only on 32-bit ABIs\n");
    return 2;
#endif
  }
  if (argc == 2 && strcmp(argv[1], "futex_time64-timeout") == 0) {
#if defined(__arm__)
    /* FUTEX_WAIT with a 0.9 s timeout (64-bit timespec), 3 times, timed with the 32-bit
     * clock_gettime, so the result does not depend on clock_gettime64. The clock of a test VM can
     * step by 1 s, so one short wait cannot be timed. A wrong timespec conversion waits 0 s. */
    int word = 0;
    struct timespec t0, t1;
    long r = 0;
    int err = 0;
    if (syscall(NR_CLOCK_GETTIME_ARM, CLOCK_MONOTONIC, &t0) != 0) return result(-1);
    for (int i = 0; i < 3; i++) {
      struct { int64_t sec; int64_t nsec; } timeout = {0, 900000000};
      r = syscall(NR_FUTEX_TIME64, &word, 0 /* FUTEX_WAIT */, 0, &timeout, NULL, 0);
      err = errno;
      if (r != -1 || err != ETIMEDOUT) break;
    }
    if (syscall(NR_CLOCK_GETTIME_ARM, CLOCK_MONOTONIC, &t1) != 0) return result(-1);
    long ms = (t1.tv_sec - t0.tv_sec) * 1000L + (t1.tv_nsec - t0.tv_nsec) / 1000000L;
    printf("futex_time64 wait 3 x 900 ms: result %ld errno %d, elapsed %ld ms\n", r, r == -1 ? err : 0, ms);
    if (r == -1 && err == ENOSYS) return 38;
    if (r != -1 || err != ETIMEDOUT) return 1;
    return (ms >= 2000 && ms <= 5000) ? 0 : 1;
#else
    printf("futex_time64 exists only on 32-bit ABIs\n");
    return 2;
#endif
  }
  if (argc >= 4 && strcmp(argv[1], "seccomp-trap") == 0) {
    return seccomp_trap(argv[2], argv + 3);
  }
  fprintf(stderr, "usage: probe futex_time64 | futex_time64-timeout | fchmodat2 <path> | clock_gettime64 | seccomp-trap <n,n> <command...>\n");
  return 2;
}
