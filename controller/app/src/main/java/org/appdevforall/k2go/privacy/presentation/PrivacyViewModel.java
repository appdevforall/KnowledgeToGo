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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The box owns the state; this keeps no copy past the last answer, and re-reads on {@link #load()}. */
public class PrivacyViewModel extends ViewModel {

    /** A one-shot result of a set, for a snackbar. {@link #take()} is true only once. */
    public static final class Notice {
        public final boolean saved;
        private boolean taken;
        Notice(boolean saved) { this.saved = saved; }
        public synchronized boolean take() {
            if (taken) return false;
            taken = true;
            return true;
        }
    }

    private final GetOutboundSwitchesUseCase getSwitches;
    private final PrivacyRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<PrivacyUiState> state = new MutableLiveData<>(
            new PrivacyUiState(PrivacyUiState.Status.LOADING, Collections.emptyList(), null));
    private final MutableLiveData<Notice> notice = new MutableLiveData<>();

    public PrivacyViewModel(GetOutboundSwitchesUseCase getSwitches, PrivacyRepository repository) {
        this.getSwitches = getSwitches;
        this.repository = repository;
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
        final List<OutboundSwitch> before = now.switches;
        state.setValue(new PrivacyUiState(PrivacyUiState.Status.READY, before, service));
        executor.execute(() -> {
            List<OutboundSwitch> after = before;
            boolean saved = false;
            try {
                after = replace(before, repository.set(service, outbound));
                saved = true;
            } catch (PrivacyException ignored) {
                // The switch goes back to the last state the box reported.
            }
            state.postValue(new PrivacyUiState(PrivacyUiState.Status.READY, after, null));
            notice.postValue(new Notice(saved));
        });
    }

    private PrivacyUiState current() {
        PrivacyUiState s = state.getValue();
        return s != null ? s : new PrivacyUiState(PrivacyUiState.Status.LOADING, Collections.emptyList(), null);
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
