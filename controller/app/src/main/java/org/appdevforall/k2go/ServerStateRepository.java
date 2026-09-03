/*
 * ============================================================================
 * Name        : ServerStateRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped, observable single source of truth for the native
 *               server state (ADFA-4578). Mirrors the InstallProgressRepository
 *               pattern. Writer: the app-level status poll (MainActivity today,
 *               a ServerController later). Readers: any tab that wants live
 *               server up/down + SystemState without depending on the Dashboard
 *               being visible.
 * ============================================================================
 */
package org.appdevforall.k2go;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public final class ServerStateRepository {

    private static final ServerStateRepository INSTANCE = new ServerStateRepository();

    public static ServerStateRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<ServerState> state = new MutableLiveData<>(ServerState.unknown());

    /** ADFA-5061: false until the poll has actually run once. */
    private volatile boolean observed = false;

    private ServerStateRepository() {
    }

    /**
     * ADFA-5061: whether {@link #current()} is an observation rather than the
     * placeholder.
     *
     * <p>The seed value reports {@code alive == false}, which is indistinguishable
     * from a box that was polled and found down — and the poll only runs while an
     * activity is alive, so a service or a freshly-created screen can read that
     * placeholder as fact. Callers that would act on "down" should check this first.
     */
    public boolean hasObservation() {
        return observed;
    }

    public LiveData<ServerState> state() {
        return state;
    }

    public ServerState current() {
        ServerState s = state.getValue();
        return s != null ? s : ServerState.unknown();
    }

    /** Thread-safe: callable from the poll's worker thread. */
    public void post(ServerState s) {
        observed = true;
        state.postValue(s);
    }
}
