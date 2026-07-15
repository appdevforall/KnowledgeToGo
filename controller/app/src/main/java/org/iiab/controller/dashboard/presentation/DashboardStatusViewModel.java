/*
 * ============================================================================
 * Name        : DashboardStatusViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Polls the local server + module endpoints off the main thread on
 *               the shared scheduler (ADFA-4457), replacing DashboardFragment's
 *               per-tick `new Thread()` ping. Exposes a DashboardStatus stream;
 *               polling is lifecycle-scoped (start in onResume, stop in onPause,
 *               cancelled in onCleared).
 * ============================================================================
 */
package org.iiab.controller.dashboard.presentation;

import org.iiab.controller.config.BoxEndpoints;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.dashboard.domain.ServerReachability;
import org.iiab.controller.util.AppExecutors;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DashboardStatusViewModel extends ViewModel {

    private static final String BASE_URL = BoxEndpoints.BASE + "/";
    private static final String HOME_URL = BASE_URL + "home";
    private static final long PERIOD_SECONDS = 5;

    private final ServerReachability reachability;
    private final MutableLiveData<DashboardStatus> state = new MutableLiveData<>();
    private volatile List<String> endpoints = Collections.emptyList();
    private ScheduledFuture<?> pollTask;

    public DashboardStatusViewModel(ServerReachability reachability) {
        this.reachability = reachability;
    }

    public LiveData<DashboardStatus> state() {
        return state;
    }

    /** Start (or refresh endpoints for) periodic polling. Safe to call on each onResume. */
    public synchronized void start(List<String> endpoints) {
        this.endpoints = endpoints != null ? endpoints : Collections.emptyList();
        if (pollTask != null && !pollTask.isCancelled()) return;
        pollTask = AppExecutors.get().scheduler()
                .scheduleWithFixedDelay(this::poll, 0, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** Stop polling (onPause); can be restarted via start(). */
    public synchronized void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    @Override
    protected void onCleared() {
        stop();
    }

    private void poll() {
        // scheduleWithFixedDelay silently cancels the task if it throws, so the
        // whole probe must be guarded.
        try {
            boolean serverAlive = reachability.isReachable(HOME_URL);
            Map<String, Boolean> modules = new HashMap<>();
            for (String endpoint : endpoints) {
                if (endpoint == null) continue;
                boolean alive = serverAlive && reachability.isReachable(BASE_URL + endpoint);
                modules.put(endpoint, alive);
            }
            state.postValue(new DashboardStatus(serverAlive, modules));
        } catch (Exception ignored) {
            // Transient probe failure: keep the poller alive for the next tick.
        }
    }
}
