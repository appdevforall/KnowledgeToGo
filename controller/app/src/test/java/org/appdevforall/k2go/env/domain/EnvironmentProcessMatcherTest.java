package org.appdevforall.k2go.env.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link EnvironmentProcessMatcher}.
 *
 * <p>The stakes are lopsided, which is why this is tested rather than eyeballed. A false negative
 * means the app starts a second environment over a live one. A false positive means it SIGKILLs a
 * process it should not have — and the process most likely to be confused with the environment is
 * an install's runrole, against the same rootfs, mid-Ansible.
 */
public class EnvironmentProcessMatcherTest {

    private static final String ROOTFS = "/data/user/0/org.appdevforall.k2go/files/rootfs/installed-rootfs/iiab";

    /** What /proc shows for the long-running environment, argv joined with spaces. */
    private static String environmentCmdline() {
        return "/data/app/org.appdevforall.k2go/lib/arm64/libproot.so --sysvipc -0 --link2symlink"
                + " --kill-on-exit -k 6.17.0-PRoot-IIAB -r " + ROOTFS
                + " -b /dev -b /proc -b /sys -w /root /bin/bash -l -c"
                + " /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin bash -lc"
                + " '/usr/local/bin/pdsm start && tail -f /dev/null'";
    }

    @Test
    public void theEnvironmentIsRecognised() {
        assertTrue(EnvironmentProcessMatcher.isOurEnvironment(environmentCmdline(), ROOTFS));
    }

    // ---- the process we must never mistake for it ----------------------------

    @Test
    public void anInstallRunroleAgainstTheSameRootfsIsNotTheEnvironment() {
        // Same binary, same rootfs, same everything except the command — and killing this one
        // would end an Ansible run mid-role. The command tail is the whole distinction.
        String runrole = "/data/app/org.appdevforall.k2go/lib/arm64/libproot.so --kill-on-exit -r "
                + ROOTFS + " -w /root /bin/bash -l -c"
                + " 'sed -i -E ... && echo kolibri_install: True >> /etc/iiab/local_vars.yml"
                + " && /usr/bin/runrole kolibri'";
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment(runrole, ROOTFS));
    }

    @Test
    public void aBackupOrCloneProotIsNotTheEnvironmentEither() {
        String backup = "/data/app/org.appdevforall.k2go/lib/arm64/libproot.so -r " + ROOTFS
                + " /bin/bash -l -c 'tar -czf /sdcard/backup.tar.gz /etc/iiab'";
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment(backup, ROOTFS));
    }

    // ---- and never another app's ---------------------------------------------

    @Test
    public void anotherAppsEnvironmentIsNotOurs() {
        // The rootfs path lives under this app's private storage, so it is the half of the rule
        // that makes the match ours rather than merely "some proot".
        String other = "/data/app/com.example.other/lib/arm64/libproot.so -r"
                + " /data/user/0/com.example.other/files/rootfs/installed-rootfs/iiab"
                + " /bin/bash -l -c 'pdsm start && tail -f /dev/null'";
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment(other, ROOTFS));
    }

    @Test
    public void theMarkerAloneIsNotEnough() {
        String unrelated = "/system/bin/sh -c tail -f /dev/null";
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment(unrelated, ROOTFS));
    }

    // ---- nothing to go on -----------------------------------------------------

    @Test
    public void unreadableOrEmptyInputMatchesNothing() {
        // /proc entries vanish while being walked and some cannot be read at all; every one of
        // those must fail closed, because the caller's next step is a SIGKILL.
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment(null, ROOTFS));
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment("", ROOTFS));
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment(environmentCmdline(), null));
        assertFalse(EnvironmentProcessMatcher.isOurEnvironment(environmentCmdline(), ""));
    }
}
