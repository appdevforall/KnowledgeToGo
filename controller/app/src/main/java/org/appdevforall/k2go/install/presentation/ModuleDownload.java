/*
 * ============================================================================
 * Name        : ModuleDownload.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394, K2GO-593. One module download through a dash-node job
 *               (maps base maps, the Forgejo binary): start, pause, resume,
 *               cancel, the hold on mobile data, and the download bar state.
 * ============================================================================
 */
package org.appdevforall.k2go.install.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.appdevforall.k2go.content.RestContentClient;
import org.appdevforall.k2go.install.domain.DownloadHold;
import org.appdevforall.k2go.install.domain.ModuleDownloadProgress;
import org.appdevforall.k2go.networkpolicy.data.AndroidNetworkClassifier;
import org.appdevforall.k2go.networkpolicy.data.OnlineAllowance;
import org.appdevforall.k2go.networkpolicy.data.SessionMeteredConsentStore;
import org.json.JSONObject;

/**
 * Owns one dash-node download job for {@link InstallService}: the REST client, who stopped it, and the
 * download bar ({@link ModuleDownloadRepository}). The box owns the transfer (aria2, resume,
 * "Reconnecting n/5"); this class only adds what the box cannot know: whether the phone's network may
 * be used now ({@link DownloadHold}).
 *
 * <p>"Who stopped it" is the one fact that only the app has: dash-node shows a user pause and a hold
 * the same way ('paused'). It lives here, for the life of this download, and nowhere else. The process
 * dies with the service that owns this object, and the next run re-attaches to the job
 * (RestContentClient) and resumes a paused job that nobody here stopped.
 *
 * <p>All methods are synchronized: the poll and the network callback run on the main thread, the
 * start runs on the queue thread.
 */
public final class ModuleDownload {

    interface Callback {
        /** The job finished. {@code result} is the job's result, or null. Runs on the main thread. */
        void onDone(@Nullable JSONObject result);

        /** The job failed or was canceled on the box. Runs on the main thread. */
        void onError(@NonNull String message);
    }

    private final Context app;
    private final String module;
    private final String jobType;
    private final JSONObject body;
    private final Callback callback;
    private final LogSink logSink;

    interface LogSink { void log(String line); }

    private RestContentClient client;   // null until the job starts
    private DownloadHold.StoppedBy stoppedBy = DownloadHold.StoppedBy.NOBODY;
    private boolean ended;
    private int lastPercent = -1;

    ModuleDownload(@NonNull Context ctx, @NonNull String module, @NonNull String jobType,
                   @NonNull JSONObject body, @NonNull Callback callback, @NonNull LogSink logSink) {
        this.app = ctx.getApplicationContext();
        this.module = module;
        this.jobType = jobType;
        this.body = body;
        this.callback = callback;
        this.logSink = logSink;
    }

    /** The OnlineAllowance kind of a module: maps is a content kind (K2GO-588), the rest are modules. */
    public static String allowanceKind(String module) {
        return "maps".equals(module)
                ? org.appdevforall.k2go.system.domain.ContentType.MAPS.key()
                : OnlineAllowance.MODULES;
    }

    String module() {
        return module;
    }

    /** Start the job, or hold it before the start when the network may not be used now. */
    synchronized void start() {
        if (ended) return;
        DownloadHold.Reason why = holdReason();
        if (why != null && AndroidNetworkClassifier.classify(app)
                != org.appdevforall.k2go.networkpolicy.domain.NetworkClass.NONE) {
            hold(why);
            return;
        }
        begin();
    }

    /** The user paused. */
    synchronized void pause() {
        if (ended) return;
        stoppedBy = DownloadHold.StoppedBy.USER;
        if (client != null) client.pause();
        ModuleDownloadRepository.get().post(ModuleDownloadProgress.paused(module, lastPercent));
        logSink.log("[" + module + "-dl] paused by the user");
    }

    /**
     * The user resumed, or allowed the network on a hold. A hold that is still true stays held: the
     * card shows why, and its button gives the consent.
     */
    synchronized void resume() {
        if (ended) return;
        DownloadHold.Reason why = holdReason();
        if (why != null) {
            stoppedBy = DownloadHold.StoppedBy.NETWORK;
            ModuleDownloadProgress cur = ModuleDownloadRepository.get().current();
            if (!(cur.isHeld() && cur.holdReason == why)) hold(why);
            return;
        }
        stoppedBy = DownloadHold.StoppedBy.NOBODY;
        logSink.log("[" + module + "-dl] resumed");
        if (client == null) {
            begin();
        } else {
            client.resume();
            ModuleDownloadRepository.get().post(ModuleDownloadProgress.active(module, lastPercent, "", -1));
        }
    }

    /** The network or the privacy choice changed: hold or continue as {@link DownloadHold} says. */
    synchronized void evaluate() {
        if (ended) return;
        DownloadHold.Action a = DownloadHold.decide(AndroidNetworkClassifier.classify(app),
                SessionMeteredConsentStore.get().isGranted(), onlineAllowed(), stoppedBy);
        if (a == DownloadHold.Action.HOLD) {
            DownloadHold.Reason why = holdReason();
            if (why != null) hold(why);
        } else if (a == DownloadHold.Action.CONTINUE) {
            stoppedBy = DownloadHold.StoppedBy.NOBODY;
            logSink.log("[" + module + "-dl] the network may be used again; continuing");
            if (client == null) begin(); else client.resume();
        }
    }

    /** Stop the job and remove its partial (the box does that on a cancel). */
    synchronized void cancel() {
        if (ended) return;
        ended = true;
        if (client != null) client.cancel();
        ModuleDownloadRepository.get().clear();
    }

    private void hold(DownloadHold.Reason why) {
        stoppedBy = DownloadHold.StoppedBy.NETWORK;
        if (client != null) client.pause();
        ModuleDownloadRepository.get().post(ModuleDownloadProgress.held(module, lastPercent, why));
        logSink.log("[" + module + "-dl] held: " + why);
    }

    @Nullable
    private DownloadHold.Reason holdReason() {
        return DownloadHold.reason(AndroidNetworkClassifier.classify(app),
                SessionMeteredConsentStore.get().isGranted(), onlineAllowed());
    }

    private boolean onlineAllowed() {
        return OnlineAllowance.allows(app, allowanceKind(module));
    }

    private void begin() {
        ModuleDownloadRepository.get().post(ModuleDownloadProgress.active(module, -1, "", -1));
        client = new RestContentClient(jobType);
        client.start(body, new RestContentClient.Listener() {
            @Override public void onProgress(int percent, String speed) { onProgress(percent, speed, -1L); }

            @Override public void onProgress(int percent, String speed, long etaSeconds) {
                synchronized (ModuleDownload.this) {
                    if (ended) return;
                    lastPercent = percent;
                    if (stoppedBy == DownloadHold.StoppedBy.NOBODY) {
                        ModuleDownloadRepository.get().post(
                                ModuleDownloadProgress.active(module, percent, speed, etaSeconds));
                    }
                }
                evaluate();
            }

            @Override public void onReconnecting(int attempt, int total) {
                synchronized (ModuleDownload.this) {
                    if (ended) return;
                    if (stoppedBy == DownloadHold.StoppedBy.NOBODY) {
                        ModuleDownloadRepository.get().post(
                                ModuleDownloadProgress.reconnecting(module, attempt, total));
                    }
                }
                evaluate();
            }

            @Override public void onPaused(int percent) {
                synchronized (ModuleDownload.this) {
                    if (ended) return;
                    if (percent >= 0) lastPercent = percent;
                    // A paused job that nobody here stopped: a job from an earlier run that this run
                    // re-attached to, or the poll before a resume took effect. Both mean: continue.
                    if (stoppedBy == DownloadHold.StoppedBy.NOBODY) {
                        if (holdReason() == null) client.resume(); else hold(holdReason());
                    }
                }
            }

            @Override public void onIndexing() { }

            @Override public void onLog(String line) { logSink.log("[" + module + "-dl] " + line); }

            @Override public void onDone() { onDone(null); }

            @Override public void onDone(JSONObject result) {
                synchronized (ModuleDownload.this) {
                    if (ended) return;
                    ended = true;
                }
                ModuleDownloadRepository.get().clear();
                callback.onDone(result);
            }

            @Override public void onError(String message) {
                synchronized (ModuleDownload.this) {
                    if (ended) return;
                    ended = true;
                }
                ModuleDownloadRepository.get().clear();
                callback.onError(message);
            }
        });
    }
}
