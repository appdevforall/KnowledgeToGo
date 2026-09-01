package org.appdevforall.k2go.sync.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link RsyncConfig} — the rsyncd.conf / URL / argv builders for
 * the Share transport (S14 step 1). Pure JVM, no Android dependencies.
 */
public class RsyncConfigTest {

    @Test
    public void daemonConfContainsAllDirectives() {
        String conf = RsyncConfig.buildDaemonConf(
                "/c/rsyncd.pid", "/c/rsyncd.lock", 8730,
                "iiab_sync", "/data/share", "iiab_peer", "/c/rsyncd.secrets");

        assertTrue(conf.startsWith("pid file = /c/rsyncd.pid\n"));
        assertTrue(conf.contains("lock file = /c/rsyncd.lock\n"));
        assertTrue(conf.contains("port = 8730\n"));
        assertTrue(conf.contains("use chroot = no\n"));
        assertTrue(conf.contains("[iiab_sync]\n"));
        assertTrue(conf.contains("path = /data/share\n"));
        assertTrue(conf.contains("read only = yes\n"));
        assertTrue(conf.contains("max connections = 3\n"));
        assertTrue(conf.contains("auth users = iiab_peer\n"));
        assertTrue(conf.contains("secrets file = /c/rsyncd.secrets\n"));
    }

    @Test
    public void remoteUrlIsWellFormed() {
        assertEquals("rsync://iiab_peer@192.168.1.50:8730/iiab_sync/",
                RsyncConfig.buildRemoteUrl("iiab_peer", "192.168.1.50", 8730, "iiab_sync"));
    }

    @Test
    public void serverArgsAreExact() {
        List<String> a = RsyncConfig.serverArgs("/lib/librsync.so", "/c/rsyncd.conf");
        assertEquals("/lib/librsync.so", a.get(0));
        assertEquals("--daemon", a.get(1));
        assertEquals("--no-detach", a.get(2));
        assertEquals("--config=/c/rsyncd.conf", a.get(3));
        assertEquals(4, a.size());
    }

    @Test
    public void clientArgsAreExact() {
        List<String> a = RsyncConfig.clientArgs(
                "/lib/librsync.so", "/c/rsync_client.pass",
                "rsync://iiab_peer@h:8730/iiab_sync/", "/dest");
        assertEquals("/lib/librsync.so", a.get(0));
        assertEquals("-av", a.get(1));
        assertEquals("--delete", a.get(2));
        assertEquals("--info=progress2", a.get(3));
        assertEquals("--partial", a.get(4));
        assertEquals("--password-file=/c/rsync_client.pass", a.get(5));
        assertEquals("rsync://iiab_peer@h:8730/iiab_sync/", a.get(6));
        assertEquals("/dest", a.get(7));
        assertEquals(8, a.size());
    }

    @Test
    public void dryRunArgsCarryDryRunAndStats() {
        List<String> a = RsyncConfig.dryRunArgs(
                "/lib/librsync.so", "/c/rsync_client.pass",
                "rsync://iiab_peer@h:8730/iiab_sync/", "/dest");
        assertTrue(a.contains("--dry-run"));
        assertTrue(a.contains("--stats"));
        assertEquals("--password-file=/c/rsync_client.pass", a.get(5));
        assertEquals(8, a.size());
    }
}
