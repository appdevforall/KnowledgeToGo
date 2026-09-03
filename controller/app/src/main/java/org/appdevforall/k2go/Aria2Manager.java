/*
 ============================================================================
 Name        : Aria2Manager.java
 Contributors: IIAB Project
 Copyright (c) 2026 IIAB Project
 Description : Java wrapper for the native libaria2c.so binary.
 ============================================================================
 */

package org.appdevforall.k2go;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.HttpURLConnection;
import java.net.URL;
import org.appdevforall.k2go.download.domain.MetalinkSplit;
import org.appdevforall.k2go.download.domain.MetalinkFile;
import org.appdevforall.k2go.download.domain.DownloadVerifier;
import org.appdevforall.k2go.download.domain.ByteToken;
import org.appdevforall.k2go.download.domain.DownloadEta;
import org.appdevforall.k2go.download.domain.Aria2ProgressLine;

public class Aria2Manager {

    private static final String TAG = "IIAB-Aria2-Native";
    /**
     * ADFA-4895: volatile for the same reason as the flag below, and it is the more important of
     * the two. It is assigned on the download thread and read by {@code stopDownload()} on the
     * caller's, so a stale null there means the process is never destroyed and the user's cancel
     * does nothing at all.
     */
    private volatile Process aria2Process;
    /**
     * ADFA-5119: why the caller asked us to stop, or {@link Stop#NONE} while running.
     *
     * <p>This was a boolean, and that is why pausing and cancelling were the same event: both set
     * it, both produced {@code onCancelled()}, and the service tore everything down either way. The
     * two are opposite intentions — one keeps the partial file and the user's decision, the other
     * discards them — so the reason has to survive as far as the listener.
     *
     * <p>Volatile: written on the caller's thread, read by the download thread's loop and its catch
     * block. Without it a stop could go unseen and be reported as a fatal error.
     */
    private volatile Stop stopRequest = Stop.NONE;

    /** Why a running download was asked to stop. */
    public enum Stop {
        /** Still running. */
        NONE,
        /** Keep the partial file and its control file; the caller intends to resume. */
        PAUSE,
        /** The caller is abandoning this download. */
        CANCEL,
        /**
         * ADFA-5119: nothing has arrived for {@link #STALL_MS} and aria2 is not going to say so.
         *
         * <p>Not a user intention — the only one here that this class raises by itself. It exists
         * because a dead transfer looks identical to a healthy idle one from aria2's side: it keeps
         * the download open, prints zero, and never exits.
         */
        STALL
    }

    /**
     * ADFA-5119: how long the transfer may report nothing before we call it stalled.
     *
     * <p>Ten seconds, chosen against a measurement rather than a feeling: on a device the rate decays
     * over about nine seconds after the connections die (24MiB → 3.0MiB → 172KiB → 0B) before it
     * reads zero at all, so a shorter window would be reacting to the decay instead of the stall.
     */
    private static final long STALL_MS = 10_000L;

    /**
     * Just the rate field. Deliberately separate from the display pattern, which requires the percent,
     * the rate AND the ETA together — a stalled line has no ETA, so that pattern cannot see the very
     * situation this one exists to catch.
     */
    private static final Pattern DL_RATE = Pattern.compile("DL:([^\\s\\]]+)");

    public interface DownloadListener {
        void onProgress(int percentage, String speed, String eta);

        /**
         * ADFA-4895: the same tick, with the figures as numbers instead of display text.
         *
         * <p>Added rather than replacing the three-argument form so the two content call sites are
         * untouched: they keep overriding that one and inherit this default, which forwards. Only a
         * caller that needs to *decide* on the transfer — rather than draw it — overrides this.
         *
         * @param bytesPerSecond current rate, or {@link ByteToken#UNKNOWN}
         * @param etaSeconds     our own estimate, or {@link DownloadEta#UNKNOWN}. Not aria2's:
         *                       see ADR-4893 for why a figure we cannot explain is a bad one to
         *                       act on.
         */
        default void onProgress(int percentage, String speed, String eta,
                                long bytesPerSecond, long etaSeconds, long completedBytes) {
            onProgress(percentage, speed, eta);
        }
        void onComplete(String downloadPath);
        void onError(String error);

        /**
         * ADFA-5119: the same failure, with what aria2 was telling us attached.
         *
         * <p>ADFA-4895 added {@link org.appdevforall.k2go.download.domain.Aria2Exit#kindOf(int)} so a
         * dropped Wi-Fi, a missing mirror and a full disk would stop being one event — and then
         * dropped the answer into the log, because the listener had nowhere to receive it. Every
         * non-zero exit still arrived as a plain {@code onError}, so the caller went on failing the
         * whole install on all of them. This is the seam that was missing.
         *
         * <p>Added as a default that forwards, like the numeric {@code onProgress} above, so the
         * content call sites are untouched. Only a caller that can act differently per kind — offer
         * a retry rather than give up — overrides it.
         *
         * @param kind what sort of stop this was; the caller owns the policy, not this class
         */
        default void onError(String error, org.appdevforall.k2go.download.domain.Aria2Exit.Kind kind) {
            onError(error);
        }
        /** ADFA-4676: post-download integrity gate failed (size/SHA-256 mismatch). */
        default void onIntegrityFailure(String reason) { onError(reason); }
        /** ADFA-4676: the user stopped the download; a clean stop, not a failure. */
        default void onCancelled() { }

        /**
         * ADFA-5119: stopped on purpose, with everything transferred so far left on disk.
         *
         * <p>Distinct from {@link #onCancelled()} because the caller's next move is opposite: after
         * a pause it will call {@code startDownload} again with the same URL and aria2 will resume
         * from the control file; after a cancellation it discards the file. Defaulting to
         * {@code onCancelled()} would be wrong for exactly that reason, so this defaults to nothing
         * and a listener that offers a pause must handle it.
         */
        default void onPaused() { }
    }

    public void startDownload(Context context, String url, DownloadListener listener) {
        stopRequest = Stop.NONE;
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                // 1. Get the path of our native binary
                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
                File aria2Bin = new File(nativeLibDir, "libaria2c.so");

                if (!aria2Bin.exists()) {
                    throw new Exception("Native aria2c binary not found at: " + aria2Bin.getAbsolutePath());
                }

                // 2. Prepare the download directory and auxiliary files
                File downloadDir = new File(context.getFilesDir(), "rootfs/downloads");
                if (!downloadDir.exists()) downloadDir.mkdirs();

                // Secure DHT file within our app (avoids the com.termux crash)
                File dhtFile = new File(context.getFilesDir(), "dht.dat");
                if (!dhtFile.exists()) {
                    try { dhtFile.createNewFile(); } catch (Exception e) { Log.w(TAG, "Could not create dht.dat"); }
                }

                // --- Extract SSL Certificate from assets ---
                File caCertFile = new File(context.getFilesDir(), "cacert.pem");
                if (!caCertFile.exists()) {
                    extractAsset(context, "cacert.pem", caCertFile);
                }
                // D6: fail closed. The downloaded rootfs is extracted and executed as
                // root, so we must never fall back to an unverified TLS connection
                // where a MITM could swap it. If the CA bundle is unavailable, abort.
                if (!caCertFile.exists()) {
                    throw new Exception("Secure download aborted: CA certificate bundle (cacert.pem) is unavailable.");
                }

                Log.d(TAG, "Executing Native Aria2c...");
                Log.d(TAG, "Target URL: " + url);

                // ADFA-4676: fetch the .meta4 once — it drives the integrity gate, the
                // --split mirror count, and reconciling whatever is already on disk so that
                // stopping and restarting a download is safe (no wasted re-download, and the
                // network profiler never trips over a leftover completed file).
                MetalinkFile mf = MetalinkSplit.isMetalinkUrl(url) ? fetchMetalink(url) : null;
                // resumeOrRepair: an existing (partial or complete-but-corrupt) file is on
                // disk. Keep it and let the main aria2 run below salvage it via
                // --check-integrity against the .meta4 piece hashes (re-downloading only the
                // damaged/missing 1 MiB pieces). Skip the profiler in that case: it lacks
                // --allow-overwrite/--check-integrity and would trip aria2 errorCode=13.
                boolean resumeOrRepair = false;
                if (mf != null && mf.canVerify()) {
                    File existing = new File(downloadDir, mf.fileName());
                    File control = new File(downloadDir, mf.fileName() + ".aria2");
                    if (existing.isFile()) {
                        long len = existing.length();
                        if (control.isFile()) {
                            // Interrupted download: aria2 keeps its .aria2 control file and
                            // pre-allocates the full size, so length alone cannot tell
                            // "complete" from "in progress". Resume via --continue /
                            // --check-integrity; do not waste a full-file hash here.
                            resumeOrRepair = true;
                            Log.d(TAG, "Interrupted download present (" + len + "/" + mf.sizeBytes()
                                    + " bytes); resuming via --check-integrity.");
                        } else if (len == mf.sizeBytes()
                                && DownloadVerifier.verify(existing, mf.sizeBytes(), mf.sha256()) == DownloadVerifier.Result.OK) {
                            // Fully downloaded and verified (no control file) -> skip download.
                            pruneStaleSiblings(downloadDir, mf.fileName());
                            Log.d(TAG, "Reusing already-verified artifact: " + mf.fileName() + " (skipping download)");
                            mainHandler.post(() -> listener.onComplete(downloadDir.getAbsolutePath()));
                            return;
                        } else if (len > mf.sizeBytes()) {
                            // Larger than the declared size: no piece map can match it. Discard.
                            Log.w(TAG, "Discarding oversized on-disk artifact (len=" + len
                                    + " > expected=" + mf.sizeBytes() + ").");
                            existing.delete();
                        } else {
                            // Complete-but-corrupt with no control file: let --check-integrity
                            // repair only the damaged pieces instead of re-downloading all.
                            resumeOrRepair = true;
                            Log.d(TAG, "Unverified artifact with no control file; will piece-repair via --check-integrity.");
                        }
                    }
                }

                // --- RUN NETWORK PROFILER (Time-boxed speed test) ---
                // UI updates are now handled inside the profiler
                // Skip speed profiling when resuming/repairing an existing file: the
                // profiler cannot write over a file that has no control file.
                // ADFA-5119: a control file on disk is enough to know this is a resume, and it is
                // known WITHOUT the metalink. That matters, because resumeOrRepair above is decided
                // from the metalink, and the metalink is fetched over the network — so exactly when
                // the network is down, the one condition that should skip the probes is unreadable
                // and both of them run in full. Twelve seconds of measuring two stacks that are
                // equally unreachable, on every retry, while the user watches the same two labels
                // scroll past for the third time.
                //
                // aria2 resumes from the control file with --continue regardless of whether we
                // recognised it, which is why the percentage survived and hid this: the transfer was
                // right, only the preamble was wasted.
                boolean resuming = resumeOrRepair || hasControlFile(downloadDir);
                boolean forceIpv4 = resuming ? false
                        : Aria2NetworkProfiler.shouldForceIpv4(aria2Bin, downloadDir, dhtFile, url, mainHandler, listener);
                // ----------------------------------------------------

                // 3. Build the command dynamically.
                // ADFA-4832 — SYNC WITH THE DASHBOARD. This flag set is the reference for the
                // in-server aria2 (static/dashboard/sockets/kiwix.socket.ts), which runs the live
                // "Get more" downloads. Keep both aligned so behaviour matches; if you change flags
                // here, mirror them there (and note any intentional divergence). Known divergences:
                // the server uses Debian's system CA (no bundled cacert), the container resolver
                // (--async-dns=false) instead of ApplyDnsUseCase + the IPv4 profiler, and has no
                // DHT/BitTorrent yet; only this app path pre-reconciles/resumes via MetalinkSplit +
                // DownloadVerifier before invoking aria2.
                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(aria2Bin.getAbsolutePath());
                command.add("--dir=" + downloadDir.getAbsolutePath());
                command.add("--continue=true");
                command.add("--allow-overwrite=true");
                command.add("--auto-file-renaming=false");
                // ADFA-4473: per-server connections stay fixed (polite); --split
                // scales with the number of HTTP mirrors in the metalink (clamp
                // and counting live in MetalinkSplit; fallback to BASE_SPLIT if
                // not a metalink / torrent / unreadable).
                int split = (mf != null) ? MetalinkSplit.splitForMirrorCount(mf.mirrors().size())
                                         : MetalinkSplit.BASE_SPLIT;
                command.add("--max-connection-per-server=" + MetalinkSplit.CONNECTIONS_PER_MIRROR);
                command.add("--split=" + split);
                command.add("--follow-metalink=mem");
                // D6: verify the SHA-256 checksums embedded in the .meta4 (Metalink)
                // while downloading. On mismatch aria2 exits non-zero, so onError fires
                // and the archive is never extracted/executed.
                command.add("--check-integrity=true");
                command.add("--enable-dht=true");
                command.add("--dht-file-path=" + dhtFile.getAbsolutePath());
                command.add("--bt-enable-lpd=true");
                command.add("--seed-time=0");

                // --- Apply SSL Certificate Validation (D6: always strict; cacert
                // presence was already enforced above, so there is no insecure path) ---
                Log.d(TAG, "Enforcing strict TLS certificate validation.");
                command.add("--check-certificate=true");
                command.add("--ca-certificate=" + caCertFile.getAbsolutePath());

                command.add("--console-log-level=warn");
                command.add("--summary-interval=1");
                command.add("--download-result=hide");

                // ADFA-4895: aria2 already knows how to survive a bad link; we were not asking it
                // to. These are set explicitly rather than inherited, so the behaviour is pinned by
                // this file and does not change under us when the bundled aria2 is rebuilt.
                //
                // No --lowest-speed-limit here on purpose. It makes aria2 abort a transfer that
                // drops below a floor, which is only safe once a caller retries and resumes: on the
                // links this product targets, a slow download is the normal case, and aborting one
                // with nothing to catch it would be worse than the stall we are trying to detect.
                // It lands with the retry loop, not before it.
                // ADFA-5119: fail fast, and let the caller own the waiting.
                //
                // These were --max-tries=5 --retry-wait=5 --timeout=60, which is up to five minutes
                // of a frozen screen before anything is reported. Measured on a device: kill the
                // Wi-Fi mid-transfer and the rate sits at its last moving average while aria2 works
                // through its budget in silence. For the rootfs there is nothing else the user can
                // do — the app has no system yet — so five silent minutes is the whole product
                // stalled.
                //
                // The budget did not shrink, it MOVED. InstallService now retries three times with
                // the attempt shown on screen ("Retry 2 of 3"), so aria2's job is to give up quickly
                // and report which kind of stop it was; ours is to decide what that deserves. One try
                // at ten seconds means a visible answer in about thirty, and nothing waits on a
                // timeout that a connectivity signal could have answered in one second.
                //
                // DIVERGENCE FROM THE DASHBOARD, on purpose. The flag block above is the reference
                // for the in-server aria2 (ADFA-4832), and these four lines are where the two now
                // differ: that one downloads content onto a LIVE system, where the user can close
                // the screen and let it work, so patience is right there and impatience would abort
                // a slow but healthy transfer. This one is the download you cannot walk away from.
                // Presence, not politeness, is what sets the numbers — do not "re-align" them.
                command.add("--max-tries=1");
                command.add("--retry-wait=5");     // default is 0 — retries hammer a struggling server
                command.add("--timeout=10");       // per-connection read timeout
                command.add("--connect-timeout=5");

                // ADFA-5119: NO --lowest-speed-limit, and this time the reason is measured rather than
                // assumed. It was added and removed within the hour: on a device, cutting the Wi-Fi
                // mid-transfer left "CN:0 SD:0 DL:0B" printing once a second for a full minute and the
                // flag never fired — because it closes CONNECTIONS whose rate is below the floor, and
                // there were none left to close. It is a per-connection guard, not a watchdog over the
                // download. Kept out for the original ADFA-4895 reason as well: on the slow links this
                // product targets it can abort a transfer that is merely slow.
                //
                // The same log also shows why aria2 cannot be the one to tell us: with --max-tries=1
                // its budget was spent on both mirrors, every URI had failed, and it still did not
                // exit. Two knobs tried, neither produces a terminal. So the stall detector lives in
                // this class instead — see the zero-rate counter in the read loop below, which has the
                // one thing aria2's own flags lack: it can tell a dead transfer from a checksum pass.

                // Apply network decision
                if (forceIpv4) {
                    Log.w(TAG, "Network profiler decided to FORCE IPv4.");
                    command.add("--disable-ipv6=true");
                }

                command.add(url);

                ProcessBuilder pb = new ProcessBuilder(command);

                // ADFA-5119 (review): last check before we spawn anything. Everything above this
                // line — the metalink fetch, the two stack probes — happens with aria2Process still
                // null, so a stop arriving in that window latches the reason and kills nothing.
                // Without this the caller's cleanup could delete the download directory and aria2
                // would then start, recreate it and write fresh residue behind it.
                if (stopRequest == Stop.CANCEL) {
                    Log.d(TAG, "Download cancelled before aria2 was started.");
                    mainHandler.post(listener::onCancelled);
                    return;
                }
                if (stopRequest == Stop.PAUSE) {
                    Log.d(TAG, "Download paused before aria2 was started.");
                    mainHandler.post(listener::onPaused);
                    return;
                }

                // Redirect errors to the same input stream
                pb.redirectErrorStream(true);
                aria2Process = pb.start();

                // 4. Read the output in real-time (stdout)
                BufferedReader reader = new BufferedReader(new InputStreamReader(aria2Process.getInputStream()));
                String line;

                // Regex to capture typical Aria2c output
                // Example: [#2089b0 400MiB/1.0GiB(39%) CN:4 DL:4.5MiB ETA:2m20s]
                Pattern pattern = Pattern.compile("\\((\\d+)%\\).*?DL:([^\\s]+).*?ETA:([^\\s\\]]+)");


                // ADFA-5119: the stall watchdog, read off the raw lines rather than the match below.
                // It has to be here and not on the parsed tick, because the stalled line does not
                // parse: "[#fb7d84 294MiB/1.9GiB(14%) CN:0 SD:0 DL:0B]" carries no ETA field, and the
                // pattern requires all three. That is also why the screen froze on its last figure
                // instead of showing zero — no tick was arriving at all.
                long zeroSince = 0L;

                while ((line = reader.readLine()) != null) {
                    if (stopRequest != Stop.NONE) {
                        aria2Process.destroy();
                        break;
                    }

                    Log.d(TAG, "[Aria2] " + line);

                    // A progress summary line — the only kind that carries a rate.
                    if (line.contains("DL:")) {
                        // A checksum pass legitimately moves no bytes, and on a 1.9 GiB archive it can
                        // run for a long while: the same device log shows "CN:0 SD:0 DL:0B" beside
                        // "[Checksum:#fb7d84 102MiB/1.9GiB(4%)]" while aria2 was repairing the file
                        // perfectly happily. A watchdog that counted those seconds would kill an
                        // integrity check — the one thing in this pipeline that must never be
                        // interrupted, since it is what stops a corrupt rootfs from being extracted.
                        if (line.contains("[Checksum:")) {
                            zeroSince = 0L;
                        } else {
                            java.util.regex.Matcher dl = DL_RATE.matcher(line);
                            long rate = dl.find() ? ByteToken.parse(dl.group(1)) : ByteToken.UNKNOWN;
                            if (rate == 0L) {
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (zeroSince == 0L) {
                                    zeroSince = now;
                                } else if (now - zeroSince >= STALL_MS) {
                                    Log.w(TAG, "no bytes for " + (STALL_MS / 1000)
                                            + "s and aria2 still has the download open — calling it stalled");
                                    requestStop(Stop.STALL);
                                    break;
                                }
                            } else if (rate > 0L) {
                                zeroSince = 0L;
                            }
                            // UNKNOWN (unreadable) neither starts nor clears the window: a line we
                            // could not parse is not evidence either way.
                        }
                    }

                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        int percent = Integer.parseInt(matcher.group(1));
                        // ADFA-4830: aria2's DL field is a transfer rate, so append the localized
                        // per-second unit here — the only place that knows the value is a rate. The
                        // profiler's status ("Test IPv4", "✓ IPv6") comes through a different path and
                        // stays unit-free, and the display strings no longer bake the unit.
                        String speed = matcher.group(2) + context.getString(R.string.k2go_rate_per_second);
                        String eta = matcher.group(3);

                        // ADFA-4895: keep the figures as numbers alongside the display text. The
                        // rate was being destroyed one line above — concatenated into a localized
                        // string — so nothing downstream could compare it to anything or divide a
                        // remaining size by it.
                        long bytesPerSecond = ByteToken.parse(matcher.group(2));
                        // The Metalink's size is the authority when we have it: it is what the
                        // integrity gate will check against, so the estimate and the verdict are
                        // measured against the same number.
                        long done = Aria2ProgressLine.completedBytes(line);
                        long total = (mf != null && mf.sizeBytes() > 0)
                                ? mf.sizeBytes() : Aria2ProgressLine.declaredTotalBytes(line);
                        long etaSeconds = DownloadEta.secondsRemaining(done, total, bytesPerSecond);
                        final long rate = bytesPerSecond;
                        final long secs = etaSeconds;
                        final long got = done;
                        mainHandler.post(() -> listener.onProgress(percent, speed, eta, rate, secs, got));
                    }
                }

                int exitCode = aria2Process.waitFor();
                Log.d(TAG, "Native Aria2c exited with code: " + exitCode);

                // ADFA-5119: report the intention, not just the fact that it stopped. Checked
                // before the exit code on purpose: SIGTERM makes aria2 exit non-zero (typically 7,
                // "unfinished downloads remained"), and a deliberate stop must never surface as an
                // error.
                if (stopRequest == Stop.PAUSE) {
                    Log.d(TAG, "Download paused by user; control file kept for resume.");
                    mainHandler.post(listener::onPaused);
                    return;
                }
                if (stopRequest == Stop.CANCEL) {
                    Log.d(TAG, "Download cancelled by user.");
                    mainHandler.post(listener::onCancelled);
                    return;
                }

                // ADFA-5119: our own verdict, reported in aria2's vocabulary so the caller has one
                // classifier to reason about. This is what finally makes Kind.STALLED reachable — it
                // was dead code while it depended on aria2's exit 5, which needs a flag that cannot
                // fire once the connections are gone.
                if (stopRequest == Stop.STALL) {
                    mainHandler.post(() -> listener.onError(
                            org.appdevforall.k2go.download.domain.Aria2Exit.label(5),
                            org.appdevforall.k2go.download.domain.Aria2Exit.Kind.STALLED));
                    return;
                }

                if (exitCode != 0) {
                    // ADFA-4895: say what aria2 said. Every non-zero exit used to produce the same
                    // sentence, so a full disk, a missing mirror and a dropped Wi-Fi were one
                    // event, and nothing downstream could tell which of them was worth retrying.
                    org.appdevforall.k2go.download.domain.Aria2Exit.Kind kind =
                            org.appdevforall.k2go.download.domain.Aria2Exit.kindOf(exitCode);
                    String reason = org.appdevforall.k2go.download.domain.Aria2Exit.label(exitCode);
                    Log.e(TAG, "aria2 exit " + exitCode + " (" + kind + "): " + reason);
                    // ADFA-5119: the kind travels with the failure now. Until this line it was
                    // computed here and read only by the log line above, so the caller could not
                    // tell a retryable stop from a hopeless one and failed the install on both.
                    mainHandler.post(() -> listener.onError(reason, kind));
                    return;
                }
                // ADFA-4676: never trust the exit code alone. For a Metalink download,
                // verify the artifact against the .meta4 <size> + file-level SHA-256
                // before reporting success, so an incomplete/corrupt/stale file cannot
                // reach extraction.
                if (mf != null && mf.canVerify()) {
                    File artifact = new File(downloadDir, mf.fileName());
                    DownloadVerifier.Result vr = DownloadVerifier.verify(artifact, mf.sizeBytes(), mf.sha256());
                    if (vr != DownloadVerifier.Result.OK) {
                        Log.e(TAG, "Integrity check failed (" + vr + ") for " + mf.fileName()
                                + " expectedSize=" + mf.sizeBytes()
                                + " actualSize=" + (artifact.exists() ? artifact.length() : -1));
                        artifact.delete();
                        new File(downloadDir, mf.fileName() + ".aria2").delete();
                        final String reason = vr.name();
                        mainHandler.post(() -> listener.onIntegrityFailure(reason));
                        return;
                    }
                    pruneStaleSiblings(downloadDir, mf.fileName());
                    Log.d(TAG, "Integrity verified: " + mf.fileName() + " (" + mf.sizeBytes() + " bytes, sha-256 OK)");
                }
                mainHandler.post(() -> listener.onComplete(downloadDir.getAbsolutePath()));

            } catch (Exception e) {
                // The stream was closed by our own stop; a deliberate stop is not a fatal error,
                // and which of the two it was still matters here (ADFA-5119).
                if (stopRequest == Stop.PAUSE) {
                    Log.d(TAG, "Download paused by user.");
                    mainHandler.post(listener::onPaused);
                    return;
                }
                if (stopRequest == Stop.CANCEL) {
                    Log.d(TAG, "Download cancelled by user.");
                    mainHandler.post(listener::onCancelled);
                    return;
                }
                // ADFA-5119 (review): a stall latched just before the exception is still a stall.
                // Without this it surfaced as a hard onError → fail(), turning a transfer that could
                // have been retried into a trip to recovery.
                if (stopRequest == Stop.STALL) {
                    mainHandler.post(() -> listener.onError(
                            org.appdevforall.k2go.download.domain.Aria2Exit.label(5),
                            org.appdevforall.k2go.download.domain.Aria2Exit.Kind.STALLED));
                    return;
                }
                Log.e(TAG, "Native Execution Error", e);
                mainHandler.post(() -> listener.onError("Fatal Error: " + e.getMessage()));
            }
        }).start();
    }

    /** Abandon this download. The partial file is left for the caller to remove. */
    public void stopDownload() {
        requestStop(Stop.CANCEL);
    }

    /**
     * ADFA-5119: stop, keeping everything transferred so far.
     *
     * <p>Resuming afterwards is a plain {@code startDownload} with the same URL: the reconcile step
     * at the top of it finds the {@code .aria2} control file and lets {@code --continue} pick up
     * where this left off.
     */
    public void pauseDownload() {
        requestStop(Stop.PAUSE);
    }

    /**
     * <b>{@code destroy()}, never {@code destroyForcibly()}.</b> destroy sends SIGTERM, which aria2
     * handles by writing its {@code .aria2} control file before exiting; SIGKILL would not give it
     * the chance, and a pause that loses the control file is a pause that restarts from zero. The
     * whole promise of the pause rests on this line.
     */
    private void requestStop(Stop reason) {
        stopRequest = reason;
        Process p = aria2Process;
        if (p != null) {
            p.destroy();
        }
    }

    /**
     * Helper method to copy files from the APK assets folder to internal storage.
     */
    /** ADFA-4676: fetch + parse the .meta4 into a verifiable MetalinkFile, or null on any error. */
    private MetalinkFile fetchMetalink(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "K2Go");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return MetalinkFile.parse(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchMetalink fallback (" + e.getMessage() + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * ADFA-5119: is there a partial download here, asked of the disk alone?
     *
     * <p>aria2 writes a {@code .aria2} control file beside whatever it is downloading and removes it
     * on completion, so its presence is the one durable "this was interrupted" fact — and unlike the
     * metalink-derived checks above, reading it needs no network. It is what a resume looks like from
     * the outside, which is why it survives the app being killed as well.
     */
    private static boolean hasControlFile(File downloadDir) {
        File[] control = downloadDir.listFiles((d, n) -> n.endsWith(".aria2"));
        return control != null && control.length > 0;
    }

    /** ADFA-4676: remove any other rootfs tarball so only the verified artifact remains. */
    private static void pruneStaleSiblings(File downloadDir, String keepName) {
        // ADFA-5119 (review): control files go too. They were left behind, and hasControlFile() —
        // which cannot name the file it is looking for without the metalink — reads ANY .aria2 as
        // "this is a resume", so one orphan from an earlier tier silently disabled the IPv4/IPv6
        // profiler on every later install. Removing them where their tarball is removed keeps the
        // two facts from drifting apart.
        File[] stale = downloadDir.listFiles((d, n) ->
                (n.endsWith(".tar.gz") || n.endsWith(".tar.xz") || n.endsWith(".aria2"))
                        && !n.equals(keepName) && !n.equals(keepName + ".aria2"));
        if (stale != null) { for (File f : stale) f.delete(); }
    }

    private void extractAsset(Context context, String assetName, File destination) {
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract " + assetName + " from assets", e);
        }
    }
}