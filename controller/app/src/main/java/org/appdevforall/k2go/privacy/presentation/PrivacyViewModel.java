/*
 * ============================================================================
 * Name        : PrivacyViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. Reads and sets the outbound switches off the main thread.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.privacy.domain.GetOutboundSwitchesUseCase;
import org.appdevforall.k2go.privacy.domain.OutboundSwitch;
import org.appdevforall.k2go.privacy.domain.PrivacyException;
import org.appdevforall.k2go.privacy.domain.PrivacyRepository;
import org.appdevforall.k2go.privacy.domain.RestartServiceUseCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The box owns the state; this keeps no copy past the last answer, and re-reads on {@link #load()}.
 *  A switch change only writes the config: the restart is the user's decision (Restart now / Later). */
public class PrivacyViewModel extends ViewModel {

    /** A one-shot event for the screen. {@link #take()} is true only once. */
    public static final class Notice {
        public enum Kind {
            /** Written: ask the user whether to restart {@code service} now. */
            ASK_RESTART,
            /** The restart of {@code service} was requested. */
            RESTARTING,
            /** A destructive operation holds the box: the restart was refused, the change waits. */
            BUSY,
            /** The box did not take the write or the restart request. */
            FAILED
        }
        public final Kind kind;
        public final String service;
        private boolean taken;
        Notice(Kind kind, String service) { this.kind = kind; this.service = service; }
        public synchronized boolean take() {
            if (taken) return false;
            taken = true;
            return true;
        }
    }

    private final GetOutboundSwitchesUseCase getSwitches;
    private final PrivacyRepository repository;
    private final RestartServiceUseCase restart;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<PrivacyUiState> state = new MutableLiveData<>(
            new PrivacyUiState(PrivacyUiState.Status.LOADING, Collections.emptyList(), null));
    private final MutableLiveData<Notice> notice = new MutableLiveData<>();

    public PrivacyViewModel(GetOutboundSwitchesUseCase getSwitches, PrivacyRepository repository,
                            RestartServiceUseCase restart) {
        this.getSwitches = getSwitches;
        this.repository = repository;
        this.restart = restart;
    }

    public LiveData<PrivacyUiState> state() { return state; }

    public LiveData<Notice> notice() { return notice; }

    public void load() {
        if (current().busy()) return;   // the set in flight posts the fresh state
        state.setValue(new PrivacyUiState(PrivacyUiState.Status.LOADING, current().switches, null));
        executor.execute(() -> {
            try {
                state.postValue(new PrivacyUiState(PrivacyUiState.Status.READY, getSwitches.execute(), null));
            } catch (PrivacyException e) {
                state.postValue(new PrivacyUiState(statusOf(e), Collections.emptyList(), null));
            }
        });
    }

    public void setOutbound(String service, boolean outbound) {
        PrivacyUiState now = current();
        if (now.busy() || now.status != PrivacyUiState.Status.READY) return;
        if (sameAsReported(now.switches, service, outbound)) return;   // nothing to change on the box
        final List<OutboundSwitch> before = now.switches;
        // While in flight the switch shows the requested value; the box's answer replaces it.
        state.setValue(new PrivacyUiState(PrivacyUiState.Status.READY,
                replace(before, new OutboundSwitch(service, true, outbound, true)), service));
        executor.execute(() -> {
            List<OutboundSwitch> after = before;   // a failure: back to what the box reported
            Notice n = new Notice(Notice.Kind.FAILED, service);
            try {
                boolean changed = repository.set(service, outbound);
                after = replace(before, new OutboundSwitch(service, true, outbound, changed));
                n = changed ? new Notice(Notice.Kind.ASK_RESTART, service) : null;
            } catch (PrivacyException ignored) {
                // The box did not take the change.
            }
            state.postValue(new PrivacyUiState(PrivacyUiState.Status.READY, after, null));
            if (n != null) notice.postValue(n);
        });
    }

    /** "Restart now": the user's permission to restart a live service. */
    public void restartNow(String service) {
        executor.execute(() -> {
            switch (restart.execute(service)) {
                case RESTARTING: notice.postValue(new Notice(Notice.Kind.RESTARTING, service)); break;
                case BUSY:       notice.postValue(new Notice(Notice.Kind.BUSY, service)); break;
                default:         notice.postValue(new Notice(Notice.Kind.FAILED, service)); break;
            }
        });
    }

    private PrivacyUiState current() {
        PrivacyUiState s = state.getValue();
        return s != null ? s : new PrivacyUiState(PrivacyUiState.Status.LOADING, Collections.emptyList(), null);
    }

    private static boolean sameAsReported(List<OutboundSwitch> list, String service, boolean outbound) {
        for (OutboundSwitch s : list) {
            if (s.service().equals(service)) return s.outbound() == outbound;
        }
        return false;
    }

    private static List<OutboundSwitch> replace(List<OutboundSwitch> list, OutboundSwitch updated) {
        List<OutboundSwitch> out = new ArrayList<>(list);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).service().equals(updated.service())) out.set(i, updated);
        }
        return out;
    }

    private static PrivacyUiState.Status statusOf(PrivacyException e) {
        switch (e.reason()) {
            case UNSUPPORTED: return PrivacyUiState.Status.UNSUPPORTED;
            case UNREACHABLE: return PrivacyUiState.Status.UNREACHABLE;
            default:          return PrivacyUiState.Status.FAILED;
        }
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
