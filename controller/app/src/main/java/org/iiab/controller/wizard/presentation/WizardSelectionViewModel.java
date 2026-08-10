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
 * recreates it. The user came back to an empty cart with no explanation, having picked
 * their way across several category screens to fill it. (Rotation does not do this:
 * {@code orientation} <em>is</em> declared.)
 *
 * <p>A {@code ViewModel} scoped to the activity outlives that and is cleared when the
 * activity really finishes, which is the lifetime this state should have had from the
 * start. It is the shape Courses already uses, and this brings ZIM and Books level with
 * it.
 *
 * <p><b>What this does NOT cover, stated plainly.</b> A {@code ViewModel} survives
 * <em>configuration changes only</em>: the store is handed on through
 * {@code onRetainNonConfigurationInstance()}, and {@code onDestroy} clears it when the
 * destruction is not a configuration change. So "Don't keep activities" and a real
 * process death still lose the cart. Closing that needs the selection written to
 * {@code onSaveInstanceState} through a {@code SavedStateHandle}, which is a larger
 * change and a separate one — recorded rather than implied, because a comment claiming
 * more coverage than the code has is worse than no comment.
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

    WizardSelectionViewModel(String initialLang, String systemDefault) {
        this.systemDefault = systemDefault == null ? "" : systemDefault;
        this.contentLang = initialLang == null ? this.systemDefault : initialLang;
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
