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
import androidx.lifecycle.AbstractSavedStateViewModelFactory;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.savedstate.SavedStateRegistryOwner;

import org.iiab.controller.wizard.data.ContentLanguageDefaults;

/**
 * Builds {@link WizardSelectionViewModel} with the language it should start from and
 * the handle it saves itself into.
 *
 * <p>The whole reason this exists: resolving that starting point reads a preference,
 * which needs a {@code Context}. Doing it inside the view model would make it
 * untestable without a device; doing it in the activity is what we are moving away
 * from. So it happens once, here, at construction — the preference does not change
 * while the wizard is open, and the view model is built once per wizard rather than
 * once per recreation.
 *
 * <p>The resolved value is only a <em>starting point</em>. If the process was killed
 * after the user chose a different language, the saved state wins; that precedence is
 * decided in the view model, where both values are in hand.
 *
 * <p>Wired by hand, as the project does everywhere else; no DI framework is involved
 * (that would be its own ADR).
 */
public class WizardSelectionViewModelFactory extends AbstractSavedStateViewModelFactory {

    private final Context appContext;

    public WizardSelectionViewModelFactory(SavedStateRegistryOwner owner, Context context) {
        super(owner, null);
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    protected <T extends ViewModel> T create(@NonNull String key, @NonNull Class<T> modelClass,
                                             @NonNull SavedStateHandle handle) {
        ContentLanguageDefaults.Choice start = ContentLanguageDefaults.resolve(appContext);
        return (T) new WizardSelectionViewModel(
                start.lang(), ContentLanguageDefaults.systemDefault(), handle);
    }
}
