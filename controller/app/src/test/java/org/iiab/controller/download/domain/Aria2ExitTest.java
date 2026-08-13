package org.iiab.controller.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.download.domain.Aria2Exit.Kind;
import org.junit.Test;

/**
 * Unit tests for {@link Aria2Exit} — reading aria2's exit code instead of discarding it. Pure JVM.
 *
 * <p>The distinction under all of these: a download worth retrying costs a little time, and one
 * that is not worth retrying costs the user data they may be paying for by the megabyte on a link
 * this product exists to work around. Both mistakes are real, so neither bucket is the default.
 */
public class Aria2ExitTest {

    @Test
    public void zeroIsTheOnlySuccess() {
        assertEquals(Kind.SUCCESS, Aria2Exit.kindOf(0));
        for (int code = 1; code <= 32; code++) {
            assertNotEquals("code " + code, Kind.SUCCESS, Aria2Exit.kindOf(code));
        }
    }

    @Test
    public void theLinkMisbehavingIsTransient() {
        for (int code : new int[]{2, 6, 7, 19, 22, 29}) {
            assertEquals("code " + code, Kind.TRANSIENT, Aria2Exit.kindOf(code));
        }
    }

    @Test
    public void aTooSlowAbortIsItsOwnKind() {
        assertEquals(Kind.STALLED, Aria2Exit.kindOf(5));
    }

    @Test
    public void theSameInputsFailingAgainIsPermanent() {
        for (int code : new int[]{3, 4, 8, 9, 16, 24}) {
            assertEquals("code " + code, Kind.PERMANENT, Aria2Exit.kindOf(code));
        }
    }

    // ---- what we refuse to guess -------------------------------------------

    /**
     * aria2's code 1 is documented as "unknown error" and covers transient conditions as well as
     * real ones. Calling it either would be inventing information; the caller decides.
     */
    @Test
    public void ariasOwnUnknownStaysUnknown() {
        assertEquals(Kind.UNKNOWN, Aria2Exit.kindOf(1));
    }

    /** A future aria2 that adds a code must not be silently sorted into a bucket. */
    @Test
    public void codesOutsideTheTableAreUnknown() {
        for (int code : new int[]{40, 99, -1, Integer.MAX_VALUE}) {
            assertEquals("code " + code, Kind.UNKNOWN, Aria2Exit.kindOf(code));
        }
    }

    // ---- the question the caller actually asks ------------------------------

    @Test
    public void onlyTransientAndStalledAreWorthAnotherAttempt() {
        assertTrue(Aria2Exit.worthAnotherAttempt(6));
        assertTrue(Aria2Exit.worthAnotherAttempt(5));
        assertFalse(Aria2Exit.worthAnotherAttempt(0));
        assertFalse(Aria2Exit.worthAnotherAttempt(9));
        assertFalse("unknown must not opt in by default", Aria2Exit.worthAnotherAttempt(1));
    }

    /**
     * A full disk is the case where retrying is actively harmful: every attempt re-downloads
     * gigabytes that cannot be written. Pinned separately because it is the one a generous default
     * would get wrong.
     */
    @Test
    public void aFullDiskIsNeverRetried() {
        assertEquals(Kind.PERMANENT, Aria2Exit.kindOf(9));
        assertFalse(Aria2Exit.worthAnotherAttempt(9));
    }

    // ---- labels -------------------------------------------------------------

    @Test
    public void everyLabelSaysSomethingAndUnknownCodesCarryTheNumber() {
        for (int code = 0; code <= 30; code++) {
            assertFalse("code " + code, Aria2Exit.label(code).trim().isEmpty());
        }
        assertTrue(Aria2Exit.label(77).contains("77"));
    }
}
