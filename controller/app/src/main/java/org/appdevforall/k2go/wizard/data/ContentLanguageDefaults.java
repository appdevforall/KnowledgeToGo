/*
 * ============================================================================
 * Name        : ContentLanguageDefaults.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Where the wizard's content language starts from (ADFA-5061).
 * ============================================================================
 */
package org.appdevforall.k2go.wizard.data;

import android.content.Context;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.applang.data.ContentLanguage;

/**
 * Resolves the content language the wizard should start with.
 *
 * <p>Lifted out of {@code SetupLibraryActivity.getZimLang()}, which read the
 * preference, normalised it and worked out whether it counted as a manual choice — a
 * fetch, inside a god class, on behalf of a screen. Moving the carts to a ViewModel
 * without moving this would have left the state in one place and the reading of it in
 * another, which is half a migration.
 *
 * <p>The starting point is the same preference the install path uses
 * ({@code selected_lang_minimal}), falling back to the phone's language. "Manual"
 * means the stored value differs from the system default — the wizard shows that as
 * "chosen" rather than "following your phone", so it has to be decided together with
 * the value and not guessed later from it.
 */
public final class ContentLanguageDefaults {

    /** A resolved starting point: the language, and whether it counts as a choice. */
    public static final class Choice {
        private final String lang;
        private final boolean manual;

        public Choice(String lang, boolean manual) {
            this.lang = lang;
            this.manual = manual;
        }

        public String lang() {
            return lang;
        }

        public boolean isManual() {
            return manual;
        }
    }

    private ContentLanguageDefaults() {
    }

    /** The language to start from, and whether it reads as manually chosen. */
    public static Choice resolve(Context ctx) {
        String system = ContentLanguage.systemDefault();
        if (ctx == null) {
            return new Choice(system, false);
        }
        String stored = ctx.getSharedPreferences(
                        ctx.getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .getString("selected_lang_minimal", system);
        String lang = ContentLanguage.normalize(stored);
        return new Choice(lang, !lang.equals(system));
    }

    /** Whether a language the user has just picked counts as a manual choice. */
    public static boolean isManual(String lang) {
        return lang != null && !lang.equals(ContentLanguage.systemDefault());
    }

    /** The phone's language, for the "follow the system" reset. */
    public static String systemDefault() {
        return ContentLanguage.systemDefault();
    }
}
