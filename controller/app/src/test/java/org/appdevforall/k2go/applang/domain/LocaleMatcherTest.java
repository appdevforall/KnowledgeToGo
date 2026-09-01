package org.appdevforall.k2go.applang.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** JVM unit test (runs in CI) for the content-language index matching. */
public class LocaleMatcherTest {

    private static final List<Locale> LIST = Arrays.asList(
            new Locale("kl", "GL"), // Greenlandic — first alphabetically in the real bug
            new Locale("en", "US"),
            new Locale("es", "ES"),
            new Locale("es", "MX"),
            new Locale("pt", "BR"));

    @Test
    public void exactLanguageCountryWins() {
        assertEquals(3, LocaleMatcher.pickIndex(LIST, "es", "MX"));
    }

    @Test
    public void languageOnlyFallsBackToFirstOfThatLanguage() {
        // No country match -> first entry with that language (es-ES at index 2).
        assertEquals(2, LocaleMatcher.pickIndex(LIST, "es", "AR"));
        assertEquals(2, LocaleMatcher.pickIndex(LIST, "es", ""));
    }

    @Test
    public void unknownLanguageFallsBackToZero_notRandom() {
        // The exact scenario from the bug: an unmatched locale must NOT drift to index 0
        // being treated as a real match elsewhere — it returns 0 deterministically.
        assertEquals(0, LocaleMatcher.pickIndex(LIST, "zz", "ZZ"));
        assertEquals(0, LocaleMatcher.pickIndex(LIST, null, null));
    }
}
