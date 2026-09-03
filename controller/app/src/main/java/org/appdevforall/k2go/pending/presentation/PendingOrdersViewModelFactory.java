/*
 * ============================================================================
 * Name        : PendingOrdersViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Manual dependency wiring for PendingOrdersViewModel (ADFA-5169):
 *               composes data -> domain -> presentation by hand, no DI framework.
 * ============================================================================
 */
package org.appdevforall.k2go.pending.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.pending.data.PendingOrdersRepositoryImpl;
import org.appdevforall.k2go.pending.domain.PendingOrdersRepository;

public class PendingOrdersViewModelFactory implements ViewModelProvider.Factory {

    private final Context ctx;

    public PendingOrdersViewModelFactory(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(PendingOrdersViewModel.class)) {
            PendingOrdersRepository repository = new PendingOrdersRepositoryImpl(ctx);
            return (T) new PendingOrdersViewModel(repository, ctx);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
