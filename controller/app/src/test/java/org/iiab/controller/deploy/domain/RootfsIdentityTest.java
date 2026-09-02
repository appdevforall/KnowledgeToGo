package org.iiab.controller.deploy.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link RootfsIdentity} — K2GO-372.
 *
 * <p>This rule now runs <em>before</em> the archive is listed, so it is the first thing that can
 * refuse a restore. The cases that matter most are the ones where it must stay silent: a rejection
 * here costs the user their restore, and an over-eager rule would refuse archives that the
 * structural fallback would have accepted.
 */
public class RootfsIdentityTest {

    private static final String ARM64 = "arm64-v8a";
    private static final String ARM32 = "armeabi-v7a";
    private static final String ROOTFS = "iiab-rootfs";

    @Test
    public void aMatchingRootfsIsNotRejected() {
        assertEquals(RootfsIdentity.Verdict.OK,
                RootfsIdentity.check(true, ROOTFS, ARM64, ARM64));
    }

    @Test
    public void theWrongAbiIsRejected() {
        // The ABI policy is strict both ways: 64-bit content never runs on a 32-bit app, and a
        // 32-bit rootfs on a 64-bit app is the mismatch the import guard exists for.
        assertEquals(RootfsIdentity.Verdict.WRONG_ARCH,
                RootfsIdentity.check(true, ROOTFS, ARM32, ARM64));
        assertEquals(RootfsIdentity.Verdict.WRONG_ARCH,
                RootfsIdentity.check(true, ROOTFS, ARM64, ARM32));
    }

    @Test
    public void somethingThatIsNotARootfsIsRejected() {
        assertEquals(RootfsIdentity.Verdict.NOT_A_ROOTFS,
                RootfsIdentity.check(true, "backup-of-something-else", ARM64, ARM64));
        assertEquals(RootfsIdentity.Verdict.NOT_A_ROOTFS,
                RootfsIdentity.check(true, null, ARM64, ARM64));
    }

    @Test
    public void noManifestIsNotARejection() {
        // Archives predate the manifest. Turning a missing hint into a hard failure here would
        // refuse files the structural fallback still accepts — the whole point of the soft path.
        assertEquals(RootfsIdentity.Verdict.OK,
                RootfsIdentity.check(false, null, null, ARM64));
        assertEquals(RootfsIdentity.Verdict.OK,
                RootfsIdentity.check(false, "anything", ARM32, ARM64));
    }

    @Test
    public void aManifestThatClaimsNoArchIsNotARejection() {
        // The manifest declares kind but leaves arch out: there is nothing to contradict, so the
        // ABI question falls through to the probe rather than being answered by silence.
        assertEquals(RootfsIdentity.Verdict.OK,
                RootfsIdentity.check(true, ROOTFS, null, ARM64));
        assertEquals(RootfsIdentity.Verdict.OK,
                RootfsIdentity.check(true, ROOTFS, "", ARM64));
    }

    @Test
    public void anUnknownAppAbiNeverRejects() {
        // If we cannot say what we are, we are in no position to refuse what the archive says.
        assertEquals(RootfsIdentity.Verdict.OK,
                RootfsIdentity.check(true, ROOTFS, ARM32, null));
    }

    @Test
    public void kindIsCheckedBeforeArch() {
        // A non-rootfs of the wrong ABI is reported as what it is, not as an ABI mismatch --
        // otherwise the message sends the user hunting for the wrong build.
        assertEquals(RootfsIdentity.Verdict.NOT_A_ROOTFS,
                RootfsIdentity.check(true, "not-a-rootfs", ARM32, ARM64));
    }
}
