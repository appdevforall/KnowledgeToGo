/*
 * ============================================================================
 * Name        : PrivacyViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. Hand-wired Data -> Domain -> Presentation for PrivacyViewModel.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.privacy.data.PrivacyRepositoryImpl;
import org.appdevforall.k2go.privacy.domain.GetOutboundSwitchesUseCase;
import org.appdevforall.k2go.privacy.domain.PrivacyRepository;

public class PrivacyViewModelFactory implements ViewModelProvider.Factory {

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(PrivacyViewModel.class)) {
            PrivacyRepository repository = new PrivacyRepositoryImpl();
            return (T) new PrivacyViewModel(new GetOutboundSwitchesUseCase(repository), repository);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
