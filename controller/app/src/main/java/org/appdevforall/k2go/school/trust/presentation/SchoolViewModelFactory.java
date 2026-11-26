/*
 * ============================================================================
 * Name        : SchoolViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: wires the Settings > School ViewModel by hand.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.school.trust.data.SchoolTrustFiles;
import org.appdevforall.k2go.util.AppExecutors;

public class SchoolViewModelFactory implements ViewModelProvider.Factory {

    private final Context app;

    public SchoolViewModelFactory(Context ctx) { app = ctx.getApplicationContext(); }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(SchoolViewModel.class)) {
            return (T) new SchoolViewModel(new SchoolTrustFiles(app.getNoBackupFilesDir()),
                    app.getContentResolver(), AppExecutors.get().io());
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
