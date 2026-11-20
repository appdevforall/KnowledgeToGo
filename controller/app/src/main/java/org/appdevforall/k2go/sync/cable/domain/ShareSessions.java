/*
 * ============================================================================
 * Name        : ShareSessions.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : What the sharing phone knows about a receiver on Wi-Fi
 *               (K2GO-584), read from its own rsync daemon log. A session
 *               from 127.0.0.1 is the cable relay; any other address is
 *               Wi-Fi. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShareSessions {

    public enum WifiPeer {
        /** No receiver reached this phone over Wi-Fi. */
        NONE,
        /** A receiver reached this phone over Wi-Fi (it is checking or copying). */
        CONNECTED,
        /** A copy over Wi-Fi ended normally and no Wi-Fi session is open. */
        ENDED
    }

    /** Copies run as several sessions in a row: a short gap between them is not the end. */
    public static final long SETTLE_MS = 5_000;

    // "2026/11/19 12:26:14 [8754] <message>"
    private static final Pattern LINE = Pattern.compile("\\[(\\d+)] (.*)$");
    // "rsync on iiab_sync/ from iiab_peer@UNDETERMINED (192.168.1.151)"
    private static final Pattern START = Pattern.compile("rsync on \\S+ from \\S* ?\\(([^)]+)\\)");

    private static final class Session {
        final boolean wifi;
        boolean sent;

        Session(boolean wifi) {
            this.wifi = wifi;
        }
    }

    private final Map<String, Session> open = new HashMap<>();
    private boolean wifiSeen;
    private boolean wifiCopied;
    private long lastWifiEnd;

    /** One log line; {@code now} is when it was read. */
    public void feed(String line, long now) {
        Matcher m = LINE.matcher(line);
        if (!m.find()) return;
        String pid = m.group(1);
        String msg = m.group(2);
        Matcher s = START.matcher(msg);
        if (s.find()) {
            boolean wifi = !isLoopback(s.group(1));
            open.put(pid, new Session(wifi));
            if (wifi) wifiSeen = true;
            return;
        }
        Session x = open.get(pid);
        if (x == null) return;
        if (msg.equals("send")) {   // "log format = %o": one line per sent file
            x.sent = true;
        } else if (msg.startsWith("sent ") && msg.contains(" bytes")) {   // normal end
            open.remove(pid);
            if (x.wifi) {
                if (x.sent) wifiCopied = true;
                lastWifiEnd = now;
            }
        } else if (msg.startsWith("rsync error") || msg.contains("connection unexpectedly closed")) {
            open.remove(pid);   // a failed or cancelled session is not a finished copy
            if (x.wifi) lastWifiEnd = now;
        }
    }

    public WifiPeer state(long now) {
        for (Session x : open.values()) {
            if (x.wifi) return WifiPeer.CONNECTED;
        }
        if (wifiCopied && now - lastWifiEnd >= SETTLE_MS) return WifiPeer.ENDED;
        return wifiSeen ? WifiPeer.CONNECTED : WifiPeer.NONE;
    }

    private static boolean isLoopback(String ip) {
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("::ffff:127.0.0.1");
    }
}
