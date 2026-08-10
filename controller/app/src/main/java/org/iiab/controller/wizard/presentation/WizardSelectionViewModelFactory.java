/*
 * ============================================================================
 * Name        : WizardSelectionViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Hand-wires the wizard selection state (ADFA-5061).
 * ============================================================================
 */
package org.iiab.controller.wizard.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.wizard.data.ContentLanguageDefaults;

/**
 * Builds {@link WizardSelectionViewModel} with the language it should start from.
 *
 * <p>The whole reason this exists: resolving that starting point reads a preference,
 * which needs a {@code Context}. Doing it inside the view model would make it
 * untestable without a device; doing it in the activity is what we are moving away
 * from. So it happens once, here, at construction — the preference does not change
 * while the wizard is open, and the view model is built once per wizard rather than
 * once per recreation.
 *
 * <p>Wired by hand, as the project does everywhere else; no DI framework is involved
 * (that would be its own ADR).
 */
public class WizardSelectionViewModelFactory implements ViewModelProvider.Factory {

    private final Context appContext;

    public WizardSelectionViewModelFactory(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        ContentLanguageDefaults.Choice start = ContentLanguageDefaults.resolve(appContext);
        return (T) new WizardSelectionViewModel(
                start.lang(), ContentLanguageDefaults.systemDefault());
    }
}
