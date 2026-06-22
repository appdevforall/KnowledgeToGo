/*
 * ============================================================================
 * Name        : DnsSettingsViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ViewModel for the Setup DNS panel; orchestrates validate/test/save off the main thread.
 * ============================================================================
 */
package org.iiab.controller.network.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.network.domain.ConfigureDnsUseCase;
import org.iiab.controller.network.domain.DnsConfig;
import org.iiab.controller.network.domain.DnsConfigRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presentation-layer ViewModel for the Setup DNS panel. Observes nothing from the
 * Data layer directly — only Domain abstractions. The Accept flow runs the
 * {@link ConfigureDnsUseCase} (validate -> probe -> save/revert) off the main thread.
 */
public class DnsSettingsViewModel extends ViewModel {

    private final DnsConfigRepository repository;
    private final ConfigureDnsUseCase configure;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<DnsSettingsUiState> state = new MutableLiveData<>();

    public DnsSettingsViewModel(DnsConfigRepository repository, ConfigureDnsUseCase configure) {
        this.repository = repository;
        this.configure = configure;
        load();
    }

    public LiveData<DnsSettingsUiState> state() {
        return state;
    }

    /** Initialise from persistence: checkbox reflects custom-enabled, fields prefilled. */
    public void load() {
        DnsConfig prefill = repository.loadCustom();
        state.postValue(DnsSettingsUiState.idle(repository.isCustomEnabled(), prefill.primary(), prefill.secondary()));
    }

    /** Checkbox toggled. Off = revert to defaults and clear the fields to the defaults. */
    public void onSetupToggled(boolean enabled) {
        if (enabled) {
            DnsConfig prefill = repository.loadCustom();
            state.postValue(DnsSettingsUiState.idle(true, prefill.primary(), prefill.secondary()));
        } else {
            executor.execute(() -> {
                repository.disableCustom();
                DnsConfig d = DnsConfig.defaults();
                state.postValue(DnsSettingsUiState.idle(false, d.primary(), d.secondary()));
            });
        }
    }

    /** Accept/save: validate, netplan-try probe, then save or revert. */
    public void onAccept(String primary, String secondary) {
        state.postValue(DnsSettingsUiState.testing(primary, secondary));
        executor.execute(() -> {
            ConfigureDnsUseCase.Result result = configure.execute(new DnsConfig(primary, secondary));
            switch (result.outcome) {
                case APPLIED:
                    state.postValue(DnsSettingsUiState.applied(primary, secondary));
                    break;
                case INVALID:
                    state.postValue(DnsSettingsUiState.invalid(primary, secondary, result.message));
                    break;
                case UNREACHABLE:
                default:
                    DnsConfig d = DnsConfig.defaults();
                    state.postValue(DnsSettingsUiState.unreachable(d.primary(), d.secondary()));
                    break;
            }
        });
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
