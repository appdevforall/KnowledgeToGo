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
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>

#define NR_FUTEX_TIME64 422 /* 32-bit ABIs only; Linux 5.1 */
#define NR_FCHMODAT2 452    /* Linux 6.6 */
#define FUTEX_WAKE_OP_CODE 1

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
  fprintf(stderr, "usage: probe futex_time64 | probe fchmodat2 <path>\n");
  return 2;
}
