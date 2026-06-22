/*
 * ============================================================================
 * Name        : DnsSettingsViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Manual DI wiring for DnsSettingsViewModel.
 * ============================================================================
 */
package org.iiab.controller.network.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.network.data.PrefsDnsConfigRepository;
import org.iiab.controller.network.data.UdpDnsConnectivityProbe;
import org.iiab.controller.network.domain.ConfigureDnsUseCase;
import org.iiab.controller.network.domain.DnsConfigRepository;
import org.iiab.controller.network.domain.DnsConnectivityProbe;

/**
 * Manual Data -> Domain -> Presentation wiring for {@link DnsSettingsViewModel}
 * (same hand-wired approach as {@code RootfsViewModelFactory}; no DI framework).
 */
public class DnsSettingsViewModelFactory implements ViewModelProvider.Factory {

    private final Context appContext;

    public DnsSettingsViewModelFactory(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(DnsSettingsViewModel.class)) {
            DnsConfigRepository repository = new PrefsDnsConfigRepository(appContext);
            DnsConnectivityProbe probe = new UdpDnsConnectivityProbe();
            ConfigureDnsUseCase configure = new ConfigureDnsUseCase(repository, probe);
            return (T) new DnsSettingsViewModel(repository, configure);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
