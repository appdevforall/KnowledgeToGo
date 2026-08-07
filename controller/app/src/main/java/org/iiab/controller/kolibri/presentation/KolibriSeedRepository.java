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
    private long seq = 0L;

    private KolibriSeedRepository() {
    }

    /** Observe with a LifecycleOwner; the observer detaches itself. */
    public LiveData<KolibriSeedState> state() {
        return state;
    }

    /** The current snapshot. Never null. */
    public KolibriSeedState current() {
        KolibriSeedState s = state.getValue();
        return s != null ? s : KolibriSeedState.idle();
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

    /** Replaces any previous session. */
    public void startSession(List<KolibriSeedState.Item> queued) {
        post(KolibriSeedState.of(queued));
    }

    public void itemStarted(int index) {
        post(current().startingItem(index));
    }

    public void itemProgress(int index, int percent, long speedBytesPerSec) {
        post(current().progress(index, percent, speedBytesPerSec));
    }

    public void itemFinished(int index, boolean ok) {
        post(current().finishItem(index, ok));
    }

    /**
     * Re-queues a failed item. Called from the retry affordance in the checklist,
     * so it runs on the main thread; the service picks the item up on its next
     * pass.
     */
    public void retryItem(int index) {
        post(current().retry(index));
    }

    /** The loop stopped, whether finished or cancelled. */
    public void sessionStopped() {
        post(current().stopped());
    }

    /** Clears the session so a new selection can start clean. */
    public void clearSession() {
        post(KolibriSeedState.idle());
    }

    /**
     * {@code postValue} rather than {@code setValue}: the writers are the REST
     * client's callbacks, which do not all arrive on the main thread.
     */
    private synchronized void post(KolibriSeedState s) {
        state.postValue(s.withSeq(++seq));
    }
}
