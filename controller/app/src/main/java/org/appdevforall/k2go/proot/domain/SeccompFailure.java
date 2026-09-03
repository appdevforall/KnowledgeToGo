/*
 * ============================================================================
 * Name        : SeccompFailure.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5362. Was this proot exit the kernel's broken seccomp?
 * ============================================================================
 */
package org.appdevforall.k2go.proot.domain;

/**
 * Recognises the one failure that {@link SeccompMode#DISABLED} fixes.
 *
 * <p><b>Why a marker and not a timeout.</b> proot names this failure itself, so there is a fact to
 * read instead of a symptom to guess at. Keying on "died quickly" would instead flip the verdict on
 * any fast failure — a broken rootfs, a bad command — and silently slow the device down for good.
 * A marker cannot false-positive: if some other kernel fails in some other way, the logs will say
 * so and this rule gets widened with evidence rather than on speculation.
 *
 * <p>Both markers are strings in the shipped proot binary. The assertion is the abort observed on
 * an affected device (Android 9, kernel 3.18); the advice line is proot's own workaround message
 * for the documented kernel bug, matched on enough of the sentence that it cannot collide with a
 * guest log line that merely names the variable.
 *
 * <p>Pure: no {@code android.*}, unit-tested on the JVM.
 */
public final class SeccompFailure {

    /** proot's assertion when the kernel delivers its seccomp event in the state proot does not expect. */
    private static final String ASSERTION_MARKER = "IS_IN_SYSENTER";

    /** proot's own advice for the documented kernel bug ("To workaround it, set the env. variable …"). */
    private static final String ADVICE_MARKER = "set the env. variable PROOT_NO_SECCOMP";

    private SeccompFailure() {
    }

    /**
     * True when this exit is proot saying it cannot use seccomp on this kernel.
     *
     * <p>Both halves are required. A zero exit is proot working, whatever it printed on the way —
     * matching output alone would misread a warning as a failure.
     *
     * @param exitCode the process exit code.
     * @param output   proot's combined output (a tail is enough — the abort ends the process).
     */
    public static boolean isSeccompAbort(int exitCode, String output) {
        if (exitCode == 0 || output == null) {
            return false;
        }
        return output.contains(ASSERTION_MARKER) || output.contains(ADVICE_MARKER);
    }
}
