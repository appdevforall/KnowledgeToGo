/*
 * ============================================================================
 * Name        : KolibriSeedRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped, observable single source of truth for the Kolibri
 *               seeding session (ADFA-4954, ADR-4954 D7).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

/**
 * Holds the seeding session for the life of the process.
 *
 * <p>{@code KolibriSeedService} is the only writer; the progress index and the
 * detail screen are readers and re-bind after a recreation. Being process-scoped
 * it outlives the Fragment/Activity lifecycle, so a rotation or a theme toggle
 * mid-download neither loses the session nor starts a second one.
 *
 * <p>Mirrors {@code ModuleQueueRepository} and {@code InstallProgressRepository}.
 * The three older content services keep this state in mutable {@code static}
 * arrays with a single {@code setListener} slot instead, and that single slot is
 * why {@code SetupProgressActivity} has to tear its detail fragment down with
 * {@code commitNow()} — the index and the detail contend for the one listener,
 * and an async teardown clobbered it (see the comment at
 * {@code SetupProgressActivity#backToIndex}). With per-observer lifecycles that
 * class of bug cannot be written, which is the whole reason this one is
 * different. See ADR-4954 D7.
 */
public final class KolibriSeedRepository {

    private static final KolibriSeedRepository INSTANCE = new KolibriSeedRepository();

    public static KolibriSeedRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<KolibriSeedState> state =
            new MutableLiveData<>(KolibriSeedState.idle());

    /**
     * The authoritative state, guarded by this object's monitor.
     *
     * <p>Not read back from the LiveData. {@code postValue} defers delivery to the
     * main thread, so {@code getValue()} still returns the previous snapshot until
     * that runnable executes. Since every transition here is derived from the
     * current one — progress, finish, retry all build on what came before — two
     * writes arriving before the main thread drains would both read the same stale
     * base and the second would overwrite the first. A finish landing on the heels
     * of a progress update would simply be lost.
     *
     * <p>{@code ModuleQueueRepository} publishes with {@code postValue} too, but
     * its callers hand it complete states rather than deriving them, so it does not
     * have this problem to solve.
     */
    private KolibriSeedState currentState = KolibriSeedState.idle();
    private long seq = 0L;

    /**
     * ADFA-5074: when the current session started, on the monotonic clock.
     *
     * <p>Only used to derive a transfer rate the box does not report. {@code elapsedRealtime}
     * rather than wall time because it is immune to the clock being adjusted mid-download, and
     * a download is exactly long enough for that to happen.
     */
    private long startedAtMs = 0L;

    private KolibriSeedRepository() {
    }

    /** Observe with a LifecycleOwner; the observer detaches itself. */
    public LiveData<KolibriSeedState> state() {
        return state;
    }

    /** The current snapshot. Never null. */
    public synchronized KolibriSeedState current() {
        return currentState;
    }

    public boolean isRunning() {
        return current().isRunning();
    }

    public boolean hasSession() {
        return current().hasSession();
    }

    public boolean isComplete() {
        return current().isComplete();
    }

    // ---- writes (service only) --------------------------------------------

    /**
     * Every mutation is read-transform-write inside one lock. Splitting it — the
     * shape {@code post(current().finishItem(i))} — would let two callbacks read
     * the same base state and have the second discard the first's transition.
     */
    private synchronized void mutate(Transition t) {
        currentState = t.apply(currentState).withSeq(++seq);
        // postValue, not setValue: the REST client's callbacks do not all arrive
        // on the main thread. Observers see the same snapshot, just slightly later.
        state.postValue(currentState);
    }

    private interface Transition {
        KolibriSeedState apply(KolibriSeedState from);
    }

    /** Replaces any previous session. */
    public synchronized void startSession(List<KolibriSeedState.Item> queued) {
        startedAtMs = android.os.SystemClock.elapsedRealtime();
        mutate(from -> KolibriSeedState.of(queued));
    }

    public void itemStarted(int index) {
        mutate(from -> from.startingItem(index));
    }

    /**
     * ADFA-5074: fills in a rate when the box does not send one.
     *
     * <p>The Kolibri job endpoint answers with a phase and a percentage; {@code speed} comes
     * back 0, so the Courses caption showed a percentage and nothing else. On a channel that
     * takes hours over a link whose speed is varying, that is not enough to tell a slow download
     * from a stopped one — which is the question being asked when someone opens this screen.
     *
     * <p>Only ever a fallback. If the box learns to report a rate, its value wins with no change
     * here: a real instant rate beats a derived average, and the two must not fight.
     *
     * <p>The clock is read here rather than in {@code TransferRate} or the state so that both
     * stay pure — the rule is testable on a JVM, and only this class knows when the session
     * began.
     */
    public synchronized void itemProgress(int index, int percent, long speedBytesPerSec) {
        final long now = android.os.SystemClock.elapsedRealtime();
        mutate(from -> {
            KolibriSeedState next = from.progress(index, percent, speedBytesPerSec);
            if (speedBytesPerSec > 0L || startedAtMs <= 0L) {
                // No start time means no session was opened through this object, so "elapsed"
                // would be time since the device booted and the rate would be fiction.
                return next;
            }
            long derived = org.iiab.controller.system.domain.TransferRate.perSecond(
                    next.transferredBytes(), now - startedAtMs);
            return derived > 0L ? next.withSpeed(derived) : next;
        });
    }

    public void itemFinished(int index, boolean ok) {
        mutate(from -> from.finishItem(index, ok));
    }

    /**
     * Re-queues a failed item. Called from the retry affordance in the checklist,
     * so it runs on the main thread; the service picks the item up on its next
     * pass.
     */
    public void retryItem(int index) {
        mutate(from -> from.retry(index));
    }

    /** The loop stopped, whether finished or cancelled. */
    public void sessionStopped() {
        mutate(KolibriSeedState::stopped);
    }

    /** Clears the session so a new selection can start clean. */
    public synchronized void clearSession() {
        startedAtMs = 0L;   // ADFA-5074: the next session brings its own start time
        mutate(from -> KolibriSeedState.idle());
    }
}
