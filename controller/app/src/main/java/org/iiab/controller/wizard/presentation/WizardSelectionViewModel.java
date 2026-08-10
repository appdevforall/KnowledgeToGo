/*
 * ============================================================================
 * Name        : WizardSelectionViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : What the user has chosen so far in the setup wizard, held where it
 *               survives an activity recreation (ADFA-5061).
 * ============================================================================
 */
package org.iiab.controller.wizard.presentation;

import android.os.Bundle;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.wizard.domain.SelectionSnapshot;

import java.util.LinkedHashMap;

/**
 * The wizard's in-flight selection: the ZIM cart, the Books cart, and the one content
 * language they share.
 *
 * <p><b>Why it moved.</b> All of this lived in plain fields on
 * {@code SetupLibraryActivity}. Fields die with the activity instance, and this one is
 * recreated whenever the configuration changes in a way it does not declare —
 * {@code uiMode} is absent from its {@code configChanges}, so a light/dark change
 * recreates it. The user came back to an empty cart with no explanation, having picked
 * their way across several category screens to fill it. (Rotation does not do this:
 * {@code orientation} <em>is</em> declared.)
 *
 * <p>A {@code ViewModel} scoped to the activity outlives that and is cleared when the
 * activity really finishes, which is the lifetime this state should have had from the
 * start. It is the shape Courses already uses, and this brings ZIM and Books level with
 * it.
 *
 * <p><b>And a ViewModel alone is not enough.</b> It survives a configuration change
 * only: the store is handed on through {@code onRetainNonConfigurationInstance()}, and
 * {@code onDestroy} clears it otherwise. "Don't keep activities" and the system killing
 * the app in the background would still have emptied the cart — and on the low-RAM
 * hardware this app targets, a background kill during a long wizard is ordinary rather
 * than exotic. So the carts are also written to {@link SavedStateHandle}, which is
 * persisted with the activity's instance state and restored when the task comes back.
 *
 * <p>They are saved <b>at save time</b>, through a saved-state provider, rather than on
 * every edit. That is what lets the maps stay live and mutable: the catalog screens go
 * on putting entries straight into them, and the provider reads whatever is there when
 * Android actually asks. Saving on each edit would have meant routing every mutation
 * through this class and rewriting those screens.
 *
 * <p><b>Note on the ADFA-5061 flags.</b> Retiring the four {@code *Wizard} booleans was
 * a different fix for a related symptom: those held a <em>decision</em>, and a decision
 * should be asked rather than remembered, which is why they became {@code ContentDoor}.
 * A cart is the user's <em>work</em>, and there is nowhere else to recover it from. So
 * one was deleted and this one is kept properly.
 *
 * <p><b>No Android here.</b> The starting language and the phone's language are handed
 * in by {@link WizardSelectionViewModelFactory}, so this class needs no {@code Context}
 * and is unit-tested on a plain JVM. "Manual" is derived rather than stored: a flag
 * beside the value can drift out of step with it, and this rule is one comparison.
 *
 * <p><b>Retained shape.</b> The maps are returned live and mutated in place by the
 * catalog screens, exactly as they were on the activity. Handing out copies would be
 * better and is a larger change across those screens; it is not smuggled in here — and
 * the test pins the identity so a well-meaning change cannot break accumulation in
 * silence.
 */
public class WizardSelectionViewModel extends ViewModel {

    /** "project|lang|flavour" -> size in bytes, accumulated across ZIM category screens. */
    private final LinkedHashMap<String, Long> zimCart = new LinkedHashMap<>();

    /** gutenberg_id -> {title, author, download_url}, handed from the Books landing. */
    private final LinkedHashMap<String, String[]> booksCart = new LinkedHashMap<>();

    /** The phone's language, for the "follow the system" reset and the manual test. */
    private final String systemDefault;

    /** The wizard's content language, shared by every catalog. */
    private String contentLang;

    private static final String SAVED = "wizard_selection";
    private static final String K_ZIM_KEYS = "zim_keys";
    private static final String K_ZIM_SIZES = "zim_sizes";
    private static final String K_BOOK_IDS = "book_ids";
    private static final String K_BOOK_TITLES = "book_titles";
    private static final String K_BOOK_AUTHORS = "book_authors";
    private static final String K_BOOK_URLS = "book_urls";
    private static final String K_LANG = "lang";

    WizardSelectionViewModel(String initialLang, String systemDefault, SavedStateHandle handle) {
        this.systemDefault = systemDefault == null ? "" : systemDefault;
        this.contentLang = initialLang == null ? this.systemDefault : initialLang;

        if (handle == null) {
            return;
        }
        restore(handle.get(SAVED));
        // Read at save time, not at edit time — see the class note.
        handle.setSavedStateProvider(SAVED, this::save);
    }

    /** Everything worth carrying across a process death, flattened. */
    private Bundle save() {
        Bundle b = new Bundle();
        b.putStringArray(K_ZIM_KEYS, SelectionSnapshot.keys(zimCart));
        b.putLongArray(K_ZIM_SIZES, SelectionSnapshot.sizes(zimCart));
        b.putStringArray(K_BOOK_IDS, SelectionSnapshot.keys(booksCart));
        b.putStringArray(K_BOOK_TITLES, SelectionSnapshot.bookColumn(booksCart, 0));
        b.putStringArray(K_BOOK_AUTHORS, SelectionSnapshot.bookColumn(booksCart, 1));
        b.putStringArray(K_BOOK_URLS, SelectionSnapshot.bookColumn(booksCart, 2));
        b.putString(K_LANG, contentLang);
        return b;
    }

    private void restore(Bundle b) {
        if (b == null) {
            return;
        }
        SelectionSnapshot.restoreZim(zimCart,
                b.getStringArray(K_ZIM_KEYS), b.getLongArray(K_ZIM_SIZES));
        SelectionSnapshot.restoreBooks(booksCart,
                b.getStringArray(K_BOOK_IDS), b.getStringArray(K_BOOK_TITLES),
                b.getStringArray(K_BOOK_AUTHORS), b.getStringArray(K_BOOK_URLS));
        // A language chosen before the kill outranks the one the factory just resolved
        // from the preference: the user picked it, and the preference did not change.
        String lang = b.getString(K_LANG);
        if (lang != null) {
            this.contentLang = lang;
        }
    }

    public LinkedHashMap<String, Long> zimCart() {
        return zimCart;
    }

    public LinkedHashMap<String, String[]> booksCart() {
        return booksCart;
    }

    public String contentLang() {
        return contentLang;
    }

    /**
     * Whether the language reads as a choice rather than as following the phone. The
     * wizard says so in words under the selector, so it has to mean exactly this.
     */
    public boolean isContentLangManual() {
        return !systemDefault.equals(contentLang);
    }

    public void setContentLang(String lang) {
        this.contentLang = lang == null ? systemDefault : lang;
    }

    /** Re-align to the phone's language. */
    public void followSystemLang() {
        this.contentLang = systemDefault;
    }
}
