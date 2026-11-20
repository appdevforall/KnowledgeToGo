package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertEquals;

import org.appdevforall.k2go.sync.cable.domain.ShareSessions.WifiPeer;
import org.junit.Test;

public class ShareSessionsTest {

    private static final String T = "2026/11/19 12:26:14 ";

    private static void session(ShareSessions s, String pid, String ip, boolean sends, long end) {
        s.feed(T + "[" + pid + "] connect from UNDETERMINED (" + ip + ")", end - 10);
        s.feed(T + "[" + pid + "] rsync on iiab_sync/ from iiab_peer@UNDETERMINED (" + ip + ")", end - 10);
        s.feed(T + "[" + pid + "] building file list", end - 9);
        if (sends) s.feed(T + "[" + pid + "] send", end - 5);
        s.feed(T + "[" + pid + "] sent 3257581 bytes  received 20552 bytes  total size 6931191754", end);
    }

    @Test
    public void nothingInTheLogIsNone() {
        ShareSessions s = new ShareSessions();
        s.feed(T + "[8748] rsyncd version 3.5.2 starting, listening on port 8730", 0);
        assertEquals(WifiPeer.NONE, s.state(0));
    }

    @Test
    public void probeConnectionsAreNotASession() {
        ShareSessions s = new ShareSessions();
        s.feed(T + "[9001] connect from UNDETERMINED (192.168.1.151)", 0);
        assertEquals(WifiPeer.NONE, s.state(0));
    }

    @Test
    public void cableSessionsAreNotWifi() {
        ShareSessions s = new ShareSessions();
        session(s, "8754", "127.0.0.1", true, 1_000);
        assertEquals(WifiPeer.NONE, s.state(60_000));
    }

    @Test
    public void openWifiSessionIsConnected() {
        ShareSessions s = new ShareSessions();
        s.feed(T + "[8754] rsync on iiab_sync/ from iiab_peer@UNDETERMINED (192.168.1.151)", 0);
        assertEquals(WifiPeer.CONNECTED, s.state(0));
    }

    @Test
    public void dryRunAloneStaysConnected() {
        ShareSessions s = new ShareSessions();
        session(s, "8754", "192.168.1.151", false, 1_000);
        assertEquals(WifiPeer.CONNECTED, s.state(60_000));
    }

    @Test
    public void finishedCopyEndsAfterTheSettleTime() {
        ShareSessions s = new ShareSessions();
        session(s, "8754", "192.168.1.151", false, 1_000);   // dry run
        session(s, "8760", "192.168.1.151", true, 20_000);   // copy
        assertEquals(WifiPeer.CONNECTED, s.state(20_000 + ShareSessions.SETTLE_MS - 1));
        assertEquals(WifiPeer.ENDED, s.state(20_000 + ShareSessions.SETTLE_MS));
    }

    @Test
    public void failedSessionIsNotAFinishedCopy() {
        ShareSessions s = new ShareSessions();
        s.feed(T + "[8760] rsync on iiab_sync/ from iiab_peer@UNDETERMINED (192.168.1.151)", 0);
        s.feed(T + "[8760] send", 1);
        s.feed(T + "[8760] rsync error: error in socket IO (code 10) at io.c(848) [sender=3.5.2]", 2);
        assertEquals(WifiPeer.CONNECTED, s.state(60_000));
    }

    @Test
    public void newSessionAfterTheEndIsConnectedAgain() {
        ShareSessions s = new ShareSessions();
        session(s, "8760", "192.168.1.151", true, 1_000);
        s.feed(T + "[8800] rsync on iiab_sync/ from iiab_peer@UNDETERMINED (192.168.1.152)", 60_000);
        assertEquals(WifiPeer.CONNECTED, s.state(60_000));
    }
}
