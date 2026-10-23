/*
 * ============================================================================
 * Name        : ImportReviewViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Hand-wires the import-review ViewModel (no DI framework): the manifest reader
 *               and the cx-core import source, plus the app context and the picked drive tree.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.contentexchange.data.CxCoreDataSource;
import org.appdevforall.k2go.contentexchange.data.CxCoreImportSource;
import org.appdevforall.k2go.contentexchange.data.ImportManifestReader;

public final class ImportReviewViewModelFactory implements ViewModelProvider.Factory {

    private final Context appContext;
    private final Uri treeUri;

    public ImportReviewViewModelFactory(Context context, Uri treeUri) {
        this.appContext = context.getApplicationContext();
        this.treeUri = treeUri;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(ImportReviewViewModel.class)) {
            return (T) new ImportReviewViewModel(appContext, treeUri,
                    new ImportManifestReader(), new CxCoreImportSource(new CxCoreDataSource()));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
