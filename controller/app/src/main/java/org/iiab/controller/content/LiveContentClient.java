/*
 * ============================================================================
 * Name        : LiveContentClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4832 (Phase 1). App-side client of the in-server dashboard
 *               socket.io channel (nginx localhost:8085 -> Node :4000). It asks
 *               the ALREADY-RUNNING server to download + index a ZIM in-process,
 *               so we never spawn a second proot over the live rootfs (which was
 *               breaking Kiwix on "Get more"). Must be driven by the foreground
 *               InstallService: the server kills the job if this socket drops, so
 *               the connection has to outlive UI/config changes.
 *
 *               Verified contract (static/dashboard/sockets/kiwix.socket.ts):
 *                 emit  start_kiwix_download  <- ["<file>.zim", ...]
 *                 on    kiwix_terminal_output <- raw aria2/index stdout (String)
 *                 on    kiwix_process_status  <- { isRunning: boolean }
 *                 on    refresh_kiwix_catalog <- (indexing finished / done)
 *               There is no explicit done/error event, so success is inferred
 *               from "Indexing complete"/refresh and failure from an error line
 *               or an isRunning:false that isn't followed by a refresh.
 * ============================================================================
 */
package org.iiab.controller.content;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.iiab.controller.config.BoxEndpoints;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.socket.client.IO;
import io.socket.client.Socket;

public final class LiveContentClient {

    /** All callbacks arrive on the socket.io worker thread; InstallProgressRepository posts are
     *  thread-safe, so the caller can forward them directly. Exactly one terminal call
     *  (onDone or onError) is guaranteed. */
    public interface Listener {
        void onProgress(int percent, String speed); // download phase (aria2)
        void onIndexing();                           // indexing phase started (indeterminate)
        void onLog(String line);                     // raw terminal line (for logs)
        void onDone();                               // success (terminal)
        void onError(String message);                // failure / unreachable (terminal)
    }

    // Same shape aria2 prints and that Aria2Manager already parses: "...(37%)...DL:34MiB..."
    private static final Pattern DL = Pattern.compile("\\((\\d+)%\\).*?DL:([^\\s]+)");
    private static final long END_GRACE_MS = 1500L;   // wait for a refresh after isRunning:false

    private final Handler main = new Handler(Looper.getMainLooper());
    private Socket socket;
    private Listener listener;
    private volatile boolean indexing = false;
    private volatile boolean finished = false;
    private Runnable pendingFail;

    /** Connects and asks the running server to add (download + index) one ZIM. */
    public void addZim(@NonNull String zimFilename, @NonNull Listener l) {
        this.listener = l;
        try {
            // Default path is "/socket.io" and reconnection is on — matches the nginx proxy.
            socket = IO.socket(BoxEndpoints.BASE);
        } catch (URISyntaxException e) {
            l.onError("bad dashboard URI: " + e.getMessage());
            return;
        }

        socket.on(Socket.EVENT_CONNECT, a ->
                socket.emit("start_kiwix_download", new JSONArray().put(zimFilename)));

        socket.on(Socket.EVENT_CONNECT_ERROR, a ->
                fail("Couldn't reach the content service"));

        socket.on("kiwix_terminal_output", a -> {
            String line = (a != null && a.length > 0 && a[0] != null) ? a[0].toString() : "";
            if (line.isEmpty()) return;
            listener.onLog(line);

            if (line.contains("already running")) { fail("Another content job is already running"); return; }
            if (line.contains("❌") || line.contains("[ERROR]")) { fail(line.trim()); return; }

            if (!indexing && (line.contains("Starting indexing") || line.contains("iiab-make-kiwix-lib"))) {
                indexing = true;
                listener.onIndexing();
            }
            if (line.contains("Indexing complete")) { done(); return; }

            if (!indexing) {
                Matcher m = DL.matcher(line);
                if (m.find()) {
                    try { listener.onProgress(Integer.parseInt(m.group(1)), m.group(2)); }
                    catch (NumberFormatException ignore) { /* partial line */ }
                }
            }
        });

        // Definitive success: the server refreshes the catalog after (re)indexing.
        socket.on("refresh_kiwix_catalog", a -> done());

        // isRunning:false is ambiguous — it fires both right before a successful refresh and on a
        // plain download failure. Wait a short grace for a refresh; if none arrives, it's a failure.
        socket.on("kiwix_process_status", a -> {
            boolean running = false;
            if (a != null && a.length > 0 && a[0] instanceof JSONObject) {
                running = ((JSONObject) a[0]).optBoolean("isRunning", false);
            }
            if (running) { cancelPendingFail(); return; }
            if (finished) return;
            cancelPendingFail();
            pendingFail = () -> { if (!finished) fail("Download did not complete"); };
            main.postDelayed(pendingFail, END_GRACE_MS);
        });

        socket.connect();
    }

    /** Best-effort cancel of an in-flight add. */
    public void cancel() {
        if (socket != null && socket.connected()) socket.emit("cancel_kiwix_download");
        teardown();
    }

    private void done() {
        if (finished) return;
        finished = true;
        cancelPendingFail();
        Listener l = listener;
        teardown();
        if (l != null) l.onDone();
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        cancelPendingFail();
        Listener l = listener;
        teardown();
        if (l != null) l.onError(message);
    }

    private void cancelPendingFail() {
        if (pendingFail != null) { main.removeCallbacks(pendingFail); pendingFail = null; }
    }

    private void teardown() {
        if (socket != null) {
            socket.off();
            socket.disconnect();
            socket.close();
            socket = null;
        }
    }
}
