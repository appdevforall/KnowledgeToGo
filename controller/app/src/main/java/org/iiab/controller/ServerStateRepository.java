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
package org.iiab.controller;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public final class ServerStateRepository {

    private static final ServerStateRepository INSTANCE = new ServerStateRepository();

    public static ServerStateRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<ServerState> state = new MutableLiveData<>(ServerState.unknown());

    private ServerStateRepository() {
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
        state.postValue(s);
    }
}
