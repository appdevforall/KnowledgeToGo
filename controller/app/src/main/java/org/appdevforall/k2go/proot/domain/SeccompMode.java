/*
 * ============================================================================
 * Name        : SeccompMode.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5362. Which of the two ways this device can run proot.
 * ============================================================================
 */
package org.appdevforall.k2go.proot.domain;

/**
 * How proot must be launched on this device.
 *
 * <p>Two rungs, not three. {@code PROOT_ASSUME_NEW_SECCOMP} — proot's other seccomp knob, which
 * forces its guess about whether the kernel evaluates seccomp after the ptrace enter-stop — was
 * measured on an affected device and aborts identically to the default, so it is not a rung.
 */
public enum SeccompMode {

    /**
     * proot's seccomp acceleration: only the syscalls proot must translate are trapped. The default,
     * and what every healthy kernel uses. Losing it costs real time — measured +18 % on a metadata
     * walk and +75 % on a read-heavy pass — which is why this is not simply switched off everywhere.
     */
    FILTER,

    /**
     * {@code PROOT_NO_SECCOMP=1}: every syscall goes through ptrace. Slower, and the only way to run
     * on a kernel whose seccomp/ptrace interaction is broken — there, proot with FILTER aborts in
     * well under a second on {@code assertion "IS_IN_SYSENTER(tracee)" failed}.
     */
    DISABLED
}
