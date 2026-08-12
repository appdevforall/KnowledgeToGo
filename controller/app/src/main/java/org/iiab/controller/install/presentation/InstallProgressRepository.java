/*
 * ============================================================================
 * Name        : InstallProgressRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped, observable single source of truth for the rootfs
 *               install pipeline's progress. Being process-scoped (not tied to a
 *               Fragment/Activity), the UI re-binds to the current InstallState
 *               after a configuration-change recreation (e.g. theme toggle) and
 *               after backgrounding. The foreground InstallService is the writer;
 *               the install UI is the reader. (ADFA-4474.)
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public final class InstallProgressRepository {

    private static final InstallProgressRepository INSTANCE = new InstallProgressRepository();

    public static InstallProgressRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<InstallState> state = new MutableLiveData<>(InstallState.idle());
    private long seq = 0L;

    /**
     * ADFA-5119: the last state this repository published, kept here rather than read back from the
     * LiveData. {@code postValue} hands the value to the main thread, so between two posts from a
     * worker thread {@code getValue()} still returns the older one — comparing against it would
     * mistake a burst of real progress for repetition and leave the stamp behind.
     */
    private InstallState lastPosted = InstallState.idle();
    // Which operation the upcoming posts belong to (ADFA-4476). Install and reset are
    // mutually exclusive (both gated by isRunning()), so one field is enough: the
    // service stamps it via beginInstall()/beginReset() before posting.
    private volatile InstallState.Op currentOp = InstallState.Op.INSTALL;

    private InstallProgressRepository() {
    }

    /** Marks the upcoming posts as belonging to the install pipeline. */
    public void beginInstall() { currentOp = InstallState.Op.INSTALL; }

    /** Marks the upcoming posts as belonging to the scratch-reset pipeline. */
    public void beginReset() { currentOp = InstallState.Op.RESET; }

    /** ADFA-5011: marks the upcoming posts as belonging to a dash-node REST-core rebuild. */
    public void beginRebuild() { currentOp = InstallState.Op.REBUILD; }

    /** The operation the current state belongs to (INSTALL when idle). */
    public InstallState.Op currentOp() { return current().op; }

    public LiveData<InstallState> state() {
        return state;
    }

    public InstallState current() {
        InstallState s = state.getValue();
        return s != null ? s : InstallState.idle();
    }

    /** True while a (non-terminal) install is in flight. Single source of truth. */
    public boolean isRunning() {
        return current().isRunning();
    }

    // All posts are thread-safe (callable from the aria2 / proot worker threads).
    public void postDownloading(int percent, String speed) { post(InstallState.downloading(percent, speed)); }
    public void postExtracting(String message)             { post(InstallState.extracting(message)); }
    public void postExtracting(int percent, String message) { post(InstallState.extracting(percent, message)); }
    public void postProvisioning(String message)           { post(InstallState.provisioning(message)); }
    public void postSuccess()                               { post(InstallState.success()); }
    public void postFailed(String message)                  { post(InstallState.failed(message)); }
    public void postIdle()                                  { post(InstallState.idle()); }

    /**
     * ADFA-5119: stamps every published state with the moment the run last MOVED.
     *
     * <p>A post that describes the same position as the previous one inherits its stamp, so the
     * figure ages while nothing happens; anything else starts the clock again. That is what lets a
     * watchdog tell a stalled install from a slow one, which no other field here can: {@code seq}
     * rises on every post, and a stalled download goes on posting.
     *
     * <p>{@code elapsedRealtime} rather than wall clock — it cannot be moved by the user or by an
     * NTP correction, and it keeps counting while the device sleeps, which a long download does.
     */
    private synchronized void post(InstallState s) {
        InstallState stamped = s.withOp(currentOp).withSeq(++seq);
        long movedAt = stamped.samePosition(lastPosted) && lastPosted.progressAtMs != 0L
                ? lastPosted.progressAtMs
                : android.os.SystemClock.elapsedRealtime();
        lastPosted = stamped.withProgressAt(movedAt);
        state.postValue(lastPosted);
    }
}
