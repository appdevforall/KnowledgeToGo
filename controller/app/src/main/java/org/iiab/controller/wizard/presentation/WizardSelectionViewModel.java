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

import androidx.lifecycle.ViewModel;

import java.util.LinkedHashMap;

/**
 * The wizard's in-flight selection: the ZIM cart, the Books cart, and the one content
 * language they share.
 *
 * <p><b>Why it moved.</b> All of this lived in plain fields on
 * {@code SetupLibraryActivity}. Fields die with the activity instance, and this one is
 * recreated whenever the configuration changes in a way it does not declare —
 * {@code uiMode} is absent from its {@code configChanges}, so a light/dark change
 * recreates it, as does "Don't keep activities" and a process death with task restore.
 * The user came back to an empty cart with no explanation, having picked their way
 * across several category screens to fill it.
 *
 * <p>A {@code ViewModel} scoped to the activity outlives exactly those recreations and
 * is cleared when the activity really finishes, which is the lifetime this state
 * should have had from the start. It is the same shape Courses already uses, and this
 * is the change that brings ZIM and Books level with it.
 *
 * <p><b>Note on the ADFA-5061 flags.</b> Retiring the four {@code *Wizard} booleans was
 * a different fix for a related symptom: those described a decision and were replaced
 * by asking the system, because a decision should never have been remembered. A cart
 * is not a decision — it is the user's work, and there is nowhere else to get it back
 * from. So this one is kept, and kept properly.
 *
 * <p><b>Retained shape.</b> The maps are returned live and mutated in place by the
 * catalog screens, exactly as they were on the activity. Handing out immutable copies
 * would be better and is a larger change across those screens; it is not smuggled in
 * here.
 */
public class WizardSelectionViewModel extends ViewModel {

    /** "project|lang|flavour" -> size in bytes, accumulated across ZIM category screens. */
    private final LinkedHashMap<String, Long> zimCart = new LinkedHashMap<>();

    /** gutenberg_id -> {title, author, download_url}, handed from the Books landing. */
    private final LinkedHashMap<String, String[]> booksCart = new LinkedHashMap<>();

    /** The wizard's content language. Null until first resolved by the activity. */
    private String contentLang = null;

    /** Whether that language was chosen rather than followed from the system. */
    private boolean contentLangManual = false;

    public LinkedHashMap<String, Long> zimCart() {
        return zimCart;
    }

    public LinkedHashMap<String, String[]> booksCart() {
        return booksCart;
    }

    public String contentLang() {
        return contentLang;
    }

    public boolean isContentLangManual() {
        return contentLangManual;
    }

    public void setContentLang(String lang, boolean manual) {
        this.contentLang = lang;
        this.contentLangManual = manual;
    }
}
