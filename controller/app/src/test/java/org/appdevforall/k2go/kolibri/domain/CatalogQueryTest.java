package org.appdevforall.k2go.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Unit tests for {@link CatalogQuery} — the filter the picker applies, and the
 * same rule the offline catalog is filtered by, so what the user sees offline is
 * what they would have seen online. Pure JVM.
 */
public class CatalogQueryTest {

    private static final String KHAN = "95a52b386f2c485cb97dd60901674a98";

    private static Channel ch(String name, String desc, String lang) {
        return Channel.builder(KHAN).name(name).description(desc)
                .language(lang, lang).build();
    }

    @Test
    public void anEmptyQueryMatchesEverything() {
        CatalogQuery q = CatalogQuery.all();
        assertTrue(q.isUnfiltered());
        assertTrue(q.matches(ch("Khan Academy", "maths", "en")));
        assertEquals(CatalogQuery.all(), CatalogQuery.of("  ", null));
        assertEquals(CatalogQuery.all(),
                CatalogQuery.of(null, Collections.<String>emptyList()));
    }

    @Test
    public void languageCodesAreLowercasedAndDeduplicated() {
        CatalogQuery q = CatalogQuery.of("", Arrays.asList("EN", "en", " ar ", "EN"));
        assertEquals(Arrays.asList("en", "ar"), q.langCodes());
    }

    @Test
    public void theLanguageFilterIsCaseInsensitiveOnBothSides() {
        // Studio's own codes carry region suffixes like pt-BR.
        CatalogQuery q = CatalogQuery.ofLanguage("PT-br");
        assertTrue(q.matches(ch("Biblioteca", "", "pt-BR")));
        assertFalse(q.matches(ch("Library", "", "en")));
    }

    @Test
    public void theKeywordMatchesNameOrDescriptionCaseInsensitively() {
        CatalogQuery q = CatalogQuery.of("STORY", null);
        assertTrue(q.matches(ch("Storybooks", "", "en")));
        assertTrue(q.matches(ch("Library", "African story collection", "en")));
        assertFalse(q.matches(ch("Khan Academy", "maths", "en")));
    }

    @Test
    public void bothAxesMustPassWhenBothAreSet() {
        CatalogQuery q = CatalogQuery.of("library", Collections.singletonList("es"));
        assertTrue(q.matches(ch("Biblioteca Library", "", "es")));
        assertFalse(q.matches(ch("Biblioteca Library", "", "en")));
        assertFalse(q.matches(ch("Khan Academy", "", "es")));
    }

    @Test
    public void blankAndNullCodesAreIgnoredRatherThanMatchingNothing() {
        CatalogQuery q = CatalogQuery.of("", Arrays.asList("", "  ", null, "fr"));
        assertEquals(Collections.singletonList("fr"), q.langCodes());
    }

    @Test
    public void aNullChannelNeverMatches() {
        assertFalse(CatalogQuery.all().matches(null));
    }

    @Test
    public void queriesWithTheSameContentAreEqual() {
        assertEquals(CatalogQuery.of("khan", Arrays.asList("en", "es")),
                CatalogQuery.of("khan", Arrays.asList("EN", "es")));
        assertEquals(CatalogQuery.of("khan", null).hashCode(),
                CatalogQuery.of("khan", null).hashCode());
    }

    @Test
    public void codesAreNotMutableFromOutside() {
        CatalogQuery q = CatalogQuery.ofLanguage("en");
        try {
            q.langCodes().add("es");
            throw new AssertionError("expected the code list to be immutable");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }
}
