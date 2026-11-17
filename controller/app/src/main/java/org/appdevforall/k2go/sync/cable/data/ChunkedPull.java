/*
 * ============================================================================
 * Name        : ChunkedPull.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The Clone receive over Wi-Fi and the cable at once (K2GO-576).
 *               1. A dry-run lists the files to copy. 2. The list is cut into
 *               chunks; a Wi-Fi rsync and a cable rsync each take the next one
 *               (--files-from). A cable that appears later takes the next free
 *               chunk; a path that fails puts its chunk back and the other path
 *               finishes it (--partial keeps what arrived). 3. A last whole-tree
 *               pull with --delete removes extra files and confirms the copy.
 *               rsync stays the only engine; this class only orders the runs.
 *               Lifecycle: Clone starts it and stops it; stop() kills its rsync
 *               processes and deletes its list files. Nothing is persisted.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.appdevforall.k2go.LogRepository;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.RsyncManager;
import org.appdevforall.k2go.boxidentity.data.CopyExcludeFiles;
import org.appdevforall.k2go.boxidentity.domain.CopyExcludes;
import org.appdevforall.k2go.sync.cable.domain.ChunkQueue;
import org.appdevforall.k2go.sync.cable.domain.DryRunFiles;
import org.appdevforall.k2go.sync.cable.domain.TransferChunk;
import org.appdevforall.k2go.sync.domain.RsyncConfig;
import org.appdevforall.k2go.sync.domain.RsyncOutcome;
import org.appdevforall.k2go.sync.domain.RsyncProgress;
import org.appdevforall.k2go.sync.domain.ShareConfig;
import org.appdevforall.k2go.sync.transport.SecretStore;
import org.appdevforall.k2go.sync.transport.TransportEngine;
import org.appdevforall.k2go.sync.transport.WifiNetworkBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

public final class ChunkedPull {

    private static final String TAG = "ChunkedPull";
    private static final long CHUNK_BYTES = 64L << 20;
    private static final int CHUNK_FILES = 2000;
    /** With no path left (no Wi-Fi, no cable) the transfer fails after this wait. */
    private static final long NO_PATH_TIMEOUT_MS = 60_000;
    private static final long WIFI_RETRY_MS = 5_000;

    /** Where each path connects. The cable port changes when the cable links again. */
    public interface Paths {
        /** Sender address on Wi-Fi, or null when there is no Wi-Fi path. */
        String wifiHost();

        int wifiPort();

        /** Local relay port of the cable, or -1 with no cable link. */
        int cablePort();

        /** The sender said that it stopped sharing. */
        boolean senderStopped();

        /** Bytes per second on each path, averaged over a few seconds. Called about once a second. */
        void onRates(long wifiBytesPerSec, long cableBytesPerSec);
    }

    private final Context app;
    private final ShareConfig config;
    private final String user;
    private final String pass;
    private final String destDir;
    private final Paths paths;
    private final TransportEngine.SyncListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final AtomicLong wifiBytes = new AtomicLong();
    private final AtomicLong cableBytes = new AtomicLong();
    // Bytes of finished chunks per path; with the running chunk this gives each path's total.
    private final AtomicLong wifiDone = new AtomicLong();
    private final AtomicLong cableDone = new AtomicLong();
    private static final int RATE_WINDOW = 5;
    private final long[][] window = new long[RATE_WINDOW][3];   // {time ms, wifi total, cable total}
    private int windowNext;
    private volatile boolean cancelled;
    private volatile int lastFailedExit;
    private long lastWifiTry;
    private int lastPct;
    private volatile ChunkQueue queue;
    private volatile Thread wifiWorker;
    private volatile Thread cableWorker;
    private File rsyncBin;
    private File passFile;
    private SecretStore secrets;

    public ChunkedPull(Context app, ShareConfig config, String user, String pass, String destDir,
                       Paths paths, TransportEngine.SyncListener listener) {
        this.app = app.getApplicationContext();
        this.config = config;
        this.user = user;
        this.pass = pass;
        this.destDir = destDir;
        this.paths = paths;
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(this::run, "clone-chunked-pull");
        t.start();
    }

    public void stop() {
        cancelled = true;
        ChunkQueue q = queue;
        if (q != null) q.cancel();
        for (Process p : processes) p.destroy();
    }

    private void run() {
        try {
            rsyncBin = new File(app.getApplicationInfo().nativeLibraryDir, "librsync.so");
            secrets = new SecretStore(app.getCacheDir());
            passFile = secrets.writeClientPassword(pass);

            List<TransferChunk.FileEntry> files = list();
            if (files == null) {
                if (!cancelled) fail(R.string.k2go_clone_cable_err_list, "file list failed on every path");
                return;
            }
            queue = new ChunkQueue(TransferChunk.plan(files, CHUNK_BYTES, CHUNK_FILES));
            Log.i(TAG, files.size() + " files, " + queue.totalBytes() + " bytes to copy");

            long lastPathAt = 0;
            long lastDone = 0;
            long lastTick = System.currentTimeMillis();
            while (!cancelled && !queue.isFinished()) {
                if (paths.senderStopped()) {
                    stop();
                    fail(R.string.k2go_clone_cable_err_sender_stopped, "the sender stopped sharing");
                    return;
                }
                startWorkers();
                long now = System.currentTimeMillis();
                long done = queue.doneBytes() + wifiBytes.get() + cableBytes.get();
                // No byte moved for a while (no path, or every path keeps failing): give up instead
                // of retrying forever. Over Wi-Fi only, this is how a stopped sender shows up.
                if (done != lastDone || lastPathAt == 0) lastPathAt = now;
                else if (now - lastPathAt > NO_PATH_TIMEOUT_MS) {
                    stop();
                    fail(R.string.k2go_clone_cable_err_no_path, "no data from the sender for "
                            + NO_PATH_TIMEOUT_MS / 1000 + " s");
                    return;
                }
                report(done, rates(now));
                lastDone = done;
                lastTick = now;
                Thread.sleep(1000);
            }
            if (cancelled) return;
            finalPass();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!cancelled) fail(R.string.k2go_clone_cable_err_no_path, String.valueOf(e));
        } finally {
            if (secrets != null) secrets.deleteClientPassword();
        }
    }

    /** The dry-run file list, over Wi-Fi first, else over the cable. Null when both fail. */
    private List<TransferChunk.FileEntry> list() throws IOException, InterruptedException {
        for (String[] target : targets()) {
            List<TransferChunk.FileEntry> files = new ArrayList<>();
            List<String> args = RsyncConfig.withExcludes(RsyncConfig.listArgs(rsyncBin.getAbsolutePath(),
                    passFile.getAbsolutePath(), url(target), destDir, DryRunFiles.OUT_FORMAT),
                    CopyExcludeFiles.rsync(app).getAbsolutePath(), CopyExcludes.rsyncProtectRules());
            int code = runRsync(args, line -> {
                TransferChunk.FileEntry e = DryRunFiles.parse(line);
                if (e != null) files.add(e);
            }, null);
            if (RsyncOutcome.isSuccess(code)) return files;
            Log.w(TAG, "file list over " + target[0] + " failed: exit " + code);
        }
        return null;
    }

    private void startWorkers() {
        // Wi-Fi joins whenever the sender answers on it, also after a cable-only start or a drop.
        long now = System.currentTimeMillis();
        if (!alive(wifiWorker) && paths.wifiHost() != null && now - lastWifiTry >= WIFI_RETRY_MS) {
            lastWifiTry = now;
            // Re-pin to the network that reaches the sender now: after Wi-Fi off/on, the old pinned
            // network is gone and every app socket would fail (rsync children are not pinned).
            WifiNetworkBinder.bindToHostNetwork(app, paths.wifiHost());
            if (reachable(paths.wifiHost(), paths.wifiPort())) wifiWorker = worker(false);
        }
        if (!alive(cableWorker) && paths.cablePort() > 0) {
            cableWorker = worker(true);
        }
    }

    private Thread worker(boolean cable) {
        Log.i(TAG, "starting the " + (cable ? "cable" : "Wi-Fi") + " path"
                + (cable ? " on 127.0.0.1:" + paths.cablePort() : " to " + paths.wifiHost()));
        Thread t = new Thread(() -> work(cable), cable ? "clone-pull-cable" : "clone-pull-wifi");
        t.start();
        return t;
    }

    /** Takes chunks until the queue is done. The cable path stops at its first failure. */
    private void work(boolean cable) {
        AtomicLong current = cable ? cableBytes : wifiBytes;
        int failures = 0;
        try {
            TransferChunk c;
            while (!cancelled && (c = queue.take()) != null) {
                String host = cable ? "127.0.0.1" : paths.wifiHost();
                int port = cable ? paths.cablePort() : paths.wifiPort();
                File list = new File(app.getCacheDir(), "clone-chunk-" + c.id + ".txt");
                int code = -1;
                if (host != null && port > 0) {
                    try (Writer w = new FileWriter(list)) {
                        for (String p : c.paths) w.write(p + "\n");
                    }
                    code = runRsync(RsyncConfig.chunkArgs(rsyncBin.getAbsolutePath(), passFile.getAbsolutePath(),
                            url(new String[]{host, String.valueOf(port)}), destDir, list.getAbsolutePath()),
                            null, current::set);
                }
                //noinspection ResultOfMethodCallIgnored
                list.delete();
                current.set(0);
                Log.i(TAG, (cable ? "cable" : "Wi-Fi") + " chunk " + c.id + " (" + c.bytes + " bytes) exit " + code);
                if (RsyncOutcome.isSuccess(code)) {
                    (cable ? cableDone : wifiDone).addAndGet(c.bytes);
                    queue.complete(c);
                    failures = 0;
                    lastFailedExit = 0;   // a recovered failure must not name a later one
                } else {
                    queue.requeue(c);
                    lastFailedExit = code;
                    Log.w(TAG, (cable ? "cable" : "Wi-Fi") + " chunk " + c.id + " failed, exit " + code + ", queued again");
                    LogRepository.get().append("[Clone] chunk " + c.id + " failed on "
                            + (cable ? "cable" : "Wi-Fi") + " (exit " + code + "), queued again");
                    if (cable || ++failures >= 2) return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.w(TAG, (cable ? "cable" : "Wi-Fi") + " path stopped: " + e.getMessage());
        }
    }

    /** The whole tree once more with --delete, over Wi-Fi first, else over the cable. */
    private void finalPass() throws IOException, InterruptedException {
        for (String[] target : targets()) {
            List<String> args = RsyncConfig.withExcludes(RsyncConfig.clientArgs(rsyncBin.getAbsolutePath(),
                    passFile.getAbsolutePath(), url(target), destDir),
                    CopyExcludeFiles.rsync(app).getAbsolutePath(), CopyExcludes.rsyncProtectRules());
            int code = runRsync(args, null, null);
            if (cancelled) return;
            if (RsyncOutcome.isSuccess(code)) {
                main.post(() -> listener.onComplete(app.getString(R.string.rsync_success_complete)));
                return;
            }
            Log.w(TAG, "final pass over " + target[0] + " failed: exit " + code);
        }
        fail(R.string.k2go_clone_cable_err_check, "final pass failed on every path");
    }

    /** Live paths, Wi-Fi first: {host, port}. Wi-Fi only when the sender answers on it. */
    private List<String[]> targets() {
        List<String[]> t = new ArrayList<>();
        if (paths.wifiHost() != null && reachable(paths.wifiHost(), paths.wifiPort())) t.add(new String[]{paths.wifiHost(), String.valueOf(paths.wifiPort())});
        if (paths.cablePort() > 0) t.add(new String[]{"127.0.0.1", String.valueOf(paths.cablePort())});
        return t;
    }

    private String url(String[] target) {
        return RsyncConfig.buildRemoteUrl(user, target[0], Integer.parseInt(target[1]), config.moduleName);
    }

    /** Runs one rsync; feeds each line and the progress2 byte count. Returns the exit code. */
    private int runRsync(List<String> args, java.util.function.Consumer<String> lines, LongConsumer bytes)
            throws IOException, InterruptedException {
        if (cancelled) return -1;
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        Process p = pb.start();
        processes.add(p);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (lines != null) lines.accept(line);
                if (bytes != null) {
                    RsyncProgress pr = RsyncProgress.parse(line);
                    if (pr != null) bytes.accept(pr.bytes);
                }
                if (line.startsWith("rsync:") || line.contains("rsync error:") || line.contains("@ERROR:")) {
                    LogRepository.get().append("[Rsync] " + line);
                }
            }
            return p.waitFor();
        } finally {
            processes.remove(p);
        }
    }

    /**
     * Per-path and total speed over the last RATE_WINDOW seconds. A chunk that finishes moves its
     * bytes from "running" to "done" at once, so a one-second delta would show a false spike.
     */
    private long rates(long now) {
        long[] cur = {now, wifiDone.get() + wifiBytes.get(), cableDone.get() + cableBytes.get()};
        long[] old = window[windowNext];
        window[windowNext] = cur;
        windowNext = (windowNext + 1) % RATE_WINDOW;
        if (old[0] == 0) return 0;
        long ms = Math.max(1, now - old[0]);
        long wifi = Math.max(0, (cur[1] - old[1]) * 1000 / ms);
        long cable = Math.max(0, (cur[2] - old[2]) * 1000 / ms);
        paths.onRates(wifi, cable);
        return wifi + cable;
    }

    private void report(long done, long bytesPerSec) {
        ChunkQueue q = queue;
        long total = q == null ? 0 : q.totalBytes();
        // ADFA-5160: never walk the bar back, also when a failed chunk goes back to the queue.
        int pct = Math.max(lastPct, total > 0 ? (int) Math.min(99, done * 100 / total) : 0);
        lastPct = pct;
        String speed = String.format(Locale.ROOT, "%.1fMB/s", bytesPerSec / 1e6);
        String eta = bytesPerSec > 0 ? RsyncProgress.formatEta(Math.max(0, total - done) / bytesPerSec) : "…";
        main.post(() -> {
            if (!cancelled) listener.onProgress(pct, speed, eta, "");
        });
    }

    /**
     * {@code userText} goes to the screen; {@code detail} only to the logs. A killed or dropped
     * rsync keeps its own message, as in the single-path pull (RsyncOutcome).
     */
    private void fail(int userText, String detail) {
        Log.e(TAG, "clone failed: " + detail + " (last failed exit " + lastFailedExit + ")");
        LogRepository.get().append("[Clone] " + detail);
        RsyncOutcome.Transfer t = RsyncOutcome.classifyTransfer(lastFailedExit);
        String msg = (t == RsyncOutcome.Transfer.KILLED || t == RsyncOutcome.Transfer.HOST_DROPPED)
                ? RsyncManager.failureText(app, lastFailedExit) : app.getString(userText);
        main.post(() -> listener.onError(msg));
    }

    private static boolean reachable(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean alive(Thread t) {
        return t != null && t.isAlive();
    }
}
