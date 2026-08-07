/*
 * ============================================================================
 * Name        : KolibriCatalogViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Manual wiring for KolibriCatalogViewModel (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.kolibri.data.CatalogRepositoryImpl;
import org.iiab.controller.kolibri.domain.CatalogRepository;
import org.iiab.controller.kolibri.domain.GetChannelCatalogUseCase;

/**
 * Composes Data → Domain → Presentation by hand, like
 * {@code RootfsViewModelFactory}. No DI framework: adopting Hilt or Dagger would
 * be a separate, explicit decision, and a per-feature factory keeps the
 * composition root from becoming a file every feature has to edit.
 */
public class KolibriCatalogViewModelFactory implements ViewModelProvider.Factory {

    private final Context appContext;

    public KolibriCatalogViewModelFactory(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(KolibriCatalogViewModel.class)) {
            CatalogRepository repository = new CatalogRepositoryImpl(appContext);
            return (T) new KolibriCatalogViewModel(new GetChannelCatalogUseCase(repository));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
