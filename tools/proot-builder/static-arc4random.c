/*
 * static-arc4random.c: the arc4random family for the static native binaries.
 * Author    : AppDevForAll
 * Copyright : Copyright (c) 2026 AppDevForAll
 *
 * ndk-static-arc4random.sh puts this object in the NDK libc.a in place of
 * arc4random.o and arc4random_uniform.o. Why: the NDK r30 bionic calls
 * madvise(MADV_WIPEONFORK) in arc4random and aborts when the call fails.
 * MADV_WIPEONFORK needs Linux 4.14, and every static program calls arc4random
 * before main(), so on an older kernel each binary stops at start (exit 134).
 *
 * These functions keep no state, so fork() needs no protection: each call
 * reads the kernel RNG through getentropy(), which uses getrandom() and falls
 * back to /dev/urandom on a kernel without it. The symbol set is the set that
 * the rest of libc.a takes from the two replaced members.
 */
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

/* In libc.a at every API level; <sys/random.h> declares it only from API 28. */
int getentropy(void* buffer, size_t length);

void arc4random_buf(void* buf, size_t n) {
  unsigned char* p = (unsigned char*)buf;
  while (n > 0) {
    size_t chunk = n > 256 ? 256 : n; /* getentropy() takes at most 256 bytes */
    if (getentropy(p, chunk) != 0) abort();
    p += chunk;
    n -= chunk;
  }
}

uint32_t arc4random(void) {
  uint32_t v;
  arc4random_buf(&v, sizeof(v));
  return v;
}

/* OpenBSD algorithm: reject values below 2**32 % upper_bound, so no modulo bias. */
uint32_t arc4random_uniform(uint32_t upper_bound) {
  uint32_t r, min;
  if (upper_bound < 2) return 0;
  min = -upper_bound % upper_bound;
  for (;;) {
    r = arc4random();
    if (r >= min) break;
  }
  return r % upper_bound;
}

/* libc_init_common calls these around fork(). There is no state to protect. */
void arc4random_mutex_lock(void) {}
void arc4random_mutex_unlock(void) {}
