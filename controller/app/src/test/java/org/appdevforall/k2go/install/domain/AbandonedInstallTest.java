package org.appdevforall.k2go.install.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.install.domain.AbandonedInstall.Work;
import org.junit.Test;

/**
 * Unit tests for the rule behind Cancel (ADFA-5119). Pure JVM.
 *
 * <p>These are here for one case in particular. {@code ACTION_CANCEL} is shared by four kinds of
 * work, and the cleanup that Cancel owes a half-downloaded rootfs would, applied to a module
 * install, clear {@code setup_complete} on a device with a working system and route its owner into
 * the first-run wizard. That is not a case anyone would think to test on a device.
 */
public class AbandonedInstallTest {

    @Test
    public void abandoningARootfsBuildLeavesNoSystem() {
        assertTrue(AbandonedInstall.leavesNoSystem(Work.ROOTFS_BUILD));
    }

    /** The expensive mistake: a system exists and goes on existing. */
    @Test
    public void abandoningAModuleInstallLeavesTheSystemAlone() {
        assertFalse(AbandonedInstall.leavesNoSystem(Work.MODULE_QUEUE));
    }

    /** Same reason: content is added to a system that is already there. */
    @Test
    public void abandoningAContentRunLeavesTheSystemAlone() {
        assertFalse(AbandonedInstall.leavesNoSystem(Work.CONTENT));
    }

    /**
     * Deliberately out of scope, and pinned so it stays visible.
     *
     * <p>Abandoning a scratch reset genuinely does leave the device without a system, so the honest
     * answer is true. It is false because the legacy Advanced screen owns what happens next on that
     * route and ADFA-5119 does not touch it. If this assertion ever flips, the reset screen's
     * follow-up is what flipped it — not a tidy-up.
     */
    @Test
    public void abandoningAResetIsARecordedGap() {
        assertFalse("out of scope for ADFA-5119, not a claim about the reset route",
                AbandonedInstall.leavesNoSystem(Work.RESET));
    }

    /**
     * Exactly one kind of work triggers the destructive cleanup. A fifth kind added later defaults
     * to leaving the system alone, which is the safe side to default to — and this fails if someone
     * quietly adds a second one.
     */
    @Test
    public void onlyOneKindOfWorkTriggersTheCleanup() {
        int destructive = 0;
        for (Work w : Work.values()) {
            if (AbandonedInstall.leavesNoSystem(w)) destructive++;
        }
        assertTrue("expected exactly one, found " + destructive, destructive == 1);
    }
}
