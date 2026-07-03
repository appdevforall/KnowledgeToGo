package org.iiab.controller.sync.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Match our rsync processes by our unique librsync.so path in the cmdline. Pure JVM. */
public class RsyncProcessMatcherTest {

    private static final String BIN =
            "/data/app/org.iiab.controller-1/lib/arm64/librsync.so";

    @Test
    public void matchesTheDaemonParent() {
        // /proc cmdline is NUL-separated; here shown space-separated
        String cmd = BIN + " --daemon --no-detach --config=/data/.../rsyncd.conf";
        assertTrue(RsyncProcessMatcher.isOurRsyncProcess(cmd, BIN));
    }

    @Test
    public void matchesAForkedConnectionChild() {
        // rsync rewrites argv per connection but still runs our binary
        String cmd = BIN + " --server --daemon .";
        assertTrue(RsyncProcessMatcher.isOurRsyncProcess(cmd, BIN));
    }

    @Test
    public void doesNotMatchAnotherProcess() {
        assertFalse(RsyncProcessMatcher.isOurRsyncProcess("/system/bin/app_process /system/bin --application", BIN));
    }

    @Test
    public void doesNotMatchOurOwnAppProcess() {
        // our app's main process cmdline is the package name, not the binary path
        assertFalse(RsyncProcessMatcher.isOurRsyncProcess("org.iiab.controller", BIN));
    }

    @Test
    public void nullOrEmptyIsNoMatch() {
        assertFalse(RsyncProcessMatcher.isOurRsyncProcess(null, BIN));
        assertFalse(RsyncProcessMatcher.isOurRsyncProcess("", BIN));
        assertFalse(RsyncProcessMatcher.isOurRsyncProcess(BIN, null));
        assertFalse(RsyncProcessMatcher.isOurRsyncProcess(BIN, ""));
    }
}
