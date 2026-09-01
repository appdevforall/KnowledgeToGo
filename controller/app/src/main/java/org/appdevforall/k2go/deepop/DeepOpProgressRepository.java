/*
 * ============================================================================
 * Name        : DeepOpProgressRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4957. App-scoped, observable single source of truth for a deep-environment
 *               operation's progress (backup / restore / clone). Process-scoped (not tied to a
 *               Fragment/Activity), so the op screen re-binds to the current DeepOpState after a
 *               recreation or backgrounding, and a fresh screen opened from the notification lands on
 *               the live operation. The foreground DeepOpService is the writer; the op screen is the
 *               reader. Mirrors InstallProgressRepository.
 * ============================================================================
 */
package org.appdevforall.k2go.deepop;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.appdevforall.k2go.env.EnvironmentLock;

public final class DeepOpProgressRepository {

    private static final DeepOpProgressRepository INSTANCE = new DeepOpProgressRepository();

    public static DeepOpProgressRepository get() { return INSTANCE; }

    private final MutableLiveData<DeepOpState> state = new MutableLiveData<>(DeepOpState.idle());
    private long seq = 0L;

    private DeepOpProgressRepository() {}

    public LiveData<DeepOpState> state() { return state; }

    public DeepOpState current() {
        DeepOpState s = state.getValue();
        return s != null ? s : DeepOpState.idle();
    }

    /** True while a (non-terminal) deep-env op is in flight. */
    public boolean isRunning() { return current().isRunning(); }

    // All posts are thread-safe (callable from the DeepOpService worker thread).
    public void postRunning(EnvironmentLock.Owner owner, String step, int percent) { post(DeepOpState.running(owner, step, percent)); }
    public void postSuccess(EnvironmentLock.Owner owner, String message) { post(DeepOpState.success(owner, message)); }
    public void postFailed(EnvironmentLock.Owner owner, String message) { post(DeepOpState.failed(owner, message)); }
    public void postIdle() { post(DeepOpState.idle()); }

    private synchronized void post(DeepOpState s) {
        state.postValue(s.withSeq(++seq));
    }
}
