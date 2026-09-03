/*
 * ============================================================================
 * Name        : KolibriCatalogViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Manual wiring for KolibriCatalogViewModel (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.kolibri.data.CatalogRepositoryImpl;
import org.appdevforall.k2go.kolibri.domain.CatalogRepository;
import org.appdevforall.k2go.kolibri.domain.GetChannelCatalogUseCase;
import org.appdevforall.k2go.kolibri.domain.GetTopicTreeUseCase;

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
        // The topic picker's navigation state. Same factory rather than a second one:
        // both view models compose from the same repository, and one composition root
        // per feature is the point.
        if (modelClass.isAssignableFrom(KolibriTopicTreeViewModel.class)) {
            CatalogRepository repository = new CatalogRepositoryImpl(appContext);
            return (T) new KolibriTopicTreeViewModel(new GetTopicTreeUseCase(repository));
        }
        // ADFA-5061's model, asked on behalf of Courses: can this order run, and
        // what does the device already hold?
        if (modelClass.isAssignableFrom(KolibriReadinessViewModel.class)) {
            return (T) new KolibriReadinessViewModel(appContext);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
