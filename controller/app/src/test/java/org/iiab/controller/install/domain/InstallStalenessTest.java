package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.install.domain.InstallStaleness.Work;
import org.junit.Test;

/**
 * Unit tests for {@link InstallStaleness} — the rule that decides an install has stopped moving.
 * Pure JVM, no emulator.
 *
 * <p>The asymmetry under all of these: interrupting a healthy install is the expensive mistake, and
 * waiting longer than necessary is the cheap one. Every boundary case below is pinned on that side.
 */
public class InstallStalenessTest {

    private static final long MINUTE = 60L * 1000L;

    // ---- the ordinary reading ----------------------------------------------

    @Test
    public void silenceInsideTheBudgetIsNotStalled() {
        assertFalse(InstallStaleness.hasStalled(Work.DOWNLOAD, 44 * MINUTE));
        assertFalse(InstallStaleness.hasStalled(Work.EXTRACT, 19 * MINUTE));
        assertFalse(InstallStaleness.hasStalled(Work.PROVISION, 29 * MINUTE));
    }

    @Test
    public void silencePastTheBudgetIsStalled() {
        assertTrue(InstallStaleness.hasStalled(Work.DOWNLOAD, 46 * MINUTE));
        assertTrue(InstallStaleness.hasStalled(Work.EXTRACT, 21 * MINUTE));
        assertTrue(InstallStaleness.hasStalled(Work.PROVISION, 31 * MINUTE));
    }

    @Test
    public void aRunThatJustMovedIsNeverStalled() {
        for (Work w : Work.values()) {
            assertFalse(w.name(), InstallStaleness.hasStalled(w, 0L));
        }
    }

    // ---- why the budgets are not one number --------------------------------

    /**
     * The ordering is the point, not the exact minutes. A download reports whole percent of a
     * multi-gigabyte image, so it may legitimately sit still far longer than an extract, which
     * names the file it is writing on every update.
     */
    @Test
    public void theDownloadIsGivenTheMostRopeAndTheExtractTheLeast() {
        assertTrue(InstallStaleness.DOWNLOAD_BUDGET_MS > InstallStaleness.PROVISION_BUDGET_MS);
        assertTrue(InstallStaleness.PROVISION_BUDGET_MS > InstallStaleness.EXTRACT_BUDGET_MS);
    }

    /**
     * A download that is slow but alive must never be cut off. Worked from the figures the budget
     * was chosen against: one percent of a 2.5 GB image over a 20 KB/s link.
     */
    @Test
    public void aStepOnABarelyUsableLinkStaysWellInsideTheBudget() {
        long oneStepBytes = 2_500_000_000L / 100L;
        long msForOneStep = oneStepBytes / 20L;              // 20 KB/s, in ms
        assertFalse(InstallStaleness.hasStalled(Work.DOWNLOAD, msForOneStep));
        assertTrue("budget should leave at least double the headroom",
                InstallStaleness.DOWNLOAD_BUDGET_MS >= msForOneStep * 2);
    }

    // ---- the edges, all resolved towards waiting ----------------------------

    /** At exactly the budget we act; one millisecond short of it we do not. */
    @Test
    public void theBoundaryIsInclusiveAndOnlyThere() {
        assertTrue(InstallStaleness.hasStalled(Work.EXTRACT, InstallStaleness.EXTRACT_BUDGET_MS));
        assertFalse(InstallStaleness.hasStalled(Work.EXTRACT, InstallStaleness.EXTRACT_BUDGET_MS - 1));
    }

    /**
     * A clock that ran backwards means "I do not know how long it has been". Keeping the gate up is
     * recoverable on the next check; declaring a live install dead is not.
     */
    @Test
    public void aBackwardsClockIsNotAVerdict() {
        for (Work w : Work.values()) {
            assertFalse(w.name(), InstallStaleness.hasStalled(w, -1L));
            assertFalse(w.name(), InstallStaleness.hasStalled(w, Long.MIN_VALUE));
        }
    }

    /**
     * An unmapped phase must not inherit the tightest budget. Falling back to the widest keeps an
     * unrecognised kind of work on the safe side of the same asymmetry.
     */
    @Test
    public void anUnknownKindOfWorkGetsTheWidestBudget() {
        assertEquals(InstallStaleness.PROVISION_BUDGET_MS, InstallStaleness.budgetMs(null));
        assertFalse(InstallStaleness.hasStalled(null, InstallStaleness.EXTRACT_BUDGET_MS));
    }
}
