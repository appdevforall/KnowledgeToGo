package org.appdevforall.k2go.proot.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SeccompFailure} — ADFA-5362.
 *
 * <p>The verdict this rule produces is cached and slows the device down for as long as it stands,
 * so the tests that matter most are the ones proving it does <b>not</b> fire: an ordinary failed
 * install must not be mistaken for a broken kernel.
 */
public class SeccompFailureTest {

    /** Verbatim from an affected device (Android 9, kernel 3.18.120). */
    private static final String REAL_ABORT =
            "\n./tracee/event.c:684: int handle_tracee_event(Tracee *, int): "
                    + "assertion \"IS_IN_SYSENTER(tracee)\" failed\n"
                    + "proot warning: signal 6 received from process 31814\n";

    /** proot's other documented seccomp diagnostic, from the strings in the shipped binary. */
    private static final String PROOT_ADVICE =
            "It seems your kernel contains this bug: "
                    + "https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161\n"
                    + "To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.\n";

    @Test
    public void recognisesTheAbortSeenOnAnAffectedDevice() {
        assertTrue(SeccompFailure.isSeccompAbort(1, REAL_ABORT));
    }

    @Test
    public void recognisesProotsOwnWorkaroundAdvice() {
        assertTrue(SeccompFailure.isSeccompAbort(1, PROOT_ADVICE));
    }

    @Test
    public void anExitCodeAloneIsNeverEnough() {
        // The whole point of a marker: a failing install, a bad command, a missing rootfs all exit
        // non-zero and fast. None of them may cost the device its seccomp acceleration for good.
        assertFalse(SeccompFailure.isSeccompAbort(1, ""));
        assertFalse(SeccompFailure.isSeccompAbort(127, "bash: pdsm: command not found\n"));
        assertFalse(SeccompFailure.isSeccompAbort(2, "tar: /library: Cannot open: No such file\n"));
        assertFalse(SeccompFailure.isSeccompAbort(137, "Killed\n"));
    }

    @Test
    public void aSuccessfulRunIsNeverAnAbortWhateverItPrinted() {
        // proot warns about bindings it cannot sanitize and still works; exit 0 settles it.
        assertFalse(SeccompFailure.isSeccompAbort(0, REAL_ABORT));
        assertFalse(SeccompFailure.isSeccompAbort(0, PROOT_ADVICE));
    }

    @Test
    public void toleratesNoOutput() {
        assertFalse(SeccompFailure.isSeccompAbort(1, null));
    }

    @Test
    public void findsTheMarkerInATailOfANoisyInstall() {
        // What the engine actually passes: the last few KB of a long run, marker at the end.
        StringBuilder noisy = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            noisy.append("TASK [iiab : install something] ***\n");
        }
        noisy.append(REAL_ABORT);
        assertTrue(SeccompFailure.isSeccompAbort(1, noisy.toString()));
    }
}
