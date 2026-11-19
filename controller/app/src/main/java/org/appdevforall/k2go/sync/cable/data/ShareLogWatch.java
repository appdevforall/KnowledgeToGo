/*
 * ============================================================================
 * Name        : ShareLogWatch.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Follows the share daemon log (RsyncManager.DAEMON_LOG) so the
 *               sending phone sees a receiver on Wi-Fi (K2GO-584). Reads only;
 *               RsyncManager creates and deletes the file.
 *               Lifecycle: start() starts one reader thread, stop() ends it;
 *               the Clone screen owns both.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.appdevforall.k2go.RsyncManager;
import org.appdevforall.k2go.sync.cable.domain.ShareSessions;
import org.appdevforall.k2go.sync.cable.domain.ShareSessions.WifiPeer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public final class ShareLogWatch {

    private static final long POLL_MS = 1_000;

    private final File file;
    private final MutableLiveData<WifiPeer> peer = new MutableLiveData<>(WifiPeer.NONE);
    private Thread reader;

    public ShareLogWatch(Context ctx) {
        file = new File(ctx.getApplicationContext().getCacheDir(), RsyncManager.DAEMON_LOG);
    }

    public LiveData<WifiPeer> peer() {
        return peer;
    }

    public void start() {
        if (reader != null) return;
        reader = new Thread(this::follow, "share-log");
        reader.setDaemon(true);
        reader.start();
    }

    public void stop() {
        Thread r = reader;
        reader = null;
        if (r != null) r.interrupt();
    }

    private void follow() {
        ShareSessions sessions = new ShareSessions();
        long offset = 0;
        StringBuilder partial = new StringBuilder();
        WifiPeer last = null;
        while (!Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();
            long size = file.length();   // 0 when the file does not exist
            if (size < offset) {         // a new share started a new log
                sessions = new ShareSessions();
                offset = 0;
                partial.setLength(0);
            }
            if (size > offset) {
                try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
                    f.seek(offset);
                    byte[] b = new byte[(int) Math.min(size - offset, 64 * 1024)];
                    f.readFully(b);
                    offset += b.length;
                    partial.append(new String(b, StandardCharsets.UTF_8));
                    for (int nl; (nl = partial.indexOf("\n")) >= 0; ) {
                        sessions.feed(partial.substring(0, nl), now);
                        partial.delete(0, nl + 1);
                    }
                } catch (IOException ignored) {
                    // deleted between the size check and the read: the next poll resets
                }
            }
            WifiPeer p = size == 0 && offset == 0 ? WifiPeer.NONE : sessions.state(now);
            if (p != last) {
                last = p;
                peer.postValue(p);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
