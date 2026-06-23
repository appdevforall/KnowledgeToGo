/*
 * ============================================================================
 * Name        : UpdateViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Manual DI for UpdateViewModel.
 * ============================================================================
 */
package org.iiab.controller.update.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.update.data.DownloadManagerGateway;
import org.iiab.controller.update.domain.OtaDownloadGateway;

/** Hand-wired factory (same approach as the other slices; no DI framework). */
public class UpdateViewModelFactory implements ViewModelProvider.Factory {

    private final Context appContext;

    public UpdateViewModelFactory(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(UpdateViewModel.class)) {
            OtaDownloadGateway gateway = new DownloadManagerGateway(appContext);
            return (T) new UpdateViewModel(gateway);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
