package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** Pure-logic tests for the bundle payload-name rules. */
public class ExportFilenamesTest {

    @Test
    public void keepsAPlainName() {
        Set<String> used = new HashSet<>();
        String name = ExportFilenames.resolve("wikipedia_en_all.zim", new ItemRef("kiwix", "x"), used);
        assertEquals("wikipedia_en_all.zim", name);
    }

    @Test
    public void stripsPathAndIllegalCharacters() {
        assertEquals("a.tar", ExportFilenames.safeBaseName("/evil/../a.tar"));
        assertEquals("ab.tar", ExportFilenames.safeBaseName("a<b>.tar"));
        assertEquals("c.zim", ExportFilenames.safeBaseName("c\u0001.zim"));  // control chars dropped
    }

    @Test
    public void dropsLeadingDotsAndRejectsEmpty() {
        assertEquals("name", ExportFilenames.safeBaseName("...name"));
        assertNull(ExportFilenames.safeBaseName("   "));
        assertNull(ExportFilenames.safeBaseName("/////"));
    }

    @Test
    public void fallsBackToAddressWhenSuggestionUnusable() {
        Set<String> used = new HashSet<>();
        String name = ExportFilenames.resolve("", new ItemRef("kolibri", "chan-7"), used);
        assertEquals("kolibri_chan-7.bin", name);
    }

    @Test
    public void deduplicatesCollisions() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("calibre", "u");
        assertEquals("book.tar", ExportFilenames.resolve("book.tar", ref, used));
        assertEquals("book_2.tar", ExportFilenames.resolve("book.tar", ref, used));
        assertEquals("book_3.tar", ExportFilenames.resolve("book.tar", ref, used));
        assertTrue(used.contains("book_2.tar"));
    }

    @Test
    public void deduplicatesExtensionlessName() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("kiwix", "x");
        assertEquals("data", ExportFilenames.resolve("data", ref, used));
        assertEquals("data_2", ExportFilenames.resolve("data", ref, used));
    }

    // ---- readable names (Calibre / Kolibri opaque ids become "<title>-<short id>.<ext>") --------

    @Test
    public void readableBuildsTitleWithShortIdAndExtension() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("calibre", "a79d2c9f-4e1b-4c3a-9d2e-000000000000");
        String name = ExportFilenames.readable("Romeo y Julieta",
                "a79d2c9f-4e1b-4c3a-9d2e-000000000000.tar", ref, used);
        assertEquals("Romeo y Julieta-a79d2c9f.tar", name);
    }

    @Test
    public void readableKeepsSpacesAndAccentsAndStripsIllegal() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("kolibri", "0123456789abcdef0123456789abcdef");
        String name = ExportFilenames.readable("Matemáticas: 1/2 <intro>", "0123456789abcdef.tar",
                ref, used);
        // Accents kept, spaces kept; the FAT-illegal ":" "/" "<" ">" dropped.
        assertEquals("Matemáticas 12 intro-01234567.tar", name);
    }

    @Test
    public void readableLowercasesTheShortId() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("calibre", "ABCDEF12-0000");
        String name = ExportFilenames.readable("Title", "ABCDEF12-0000.tar", ref, used);
        assertEquals("Title-abcdef12.tar", name);
    }

    @Test
    public void readableFallsBackToResolveWhenTitleUnusable() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("kolibri", "chan-7");
        // Title sanitizes to nothing: fall back to the server-name rule.
        assertEquals("chan-7.tar", ExportFilenames.readable("///", "chan-7.tar", ref, used));
    }

    @Test
    public void readableDeduplicatesSameTitleAndId() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("calibre", "deadbeef-1111");
        assertEquals("Book-deadbeef.tar", ExportFilenames.readable("Book", "deadbeef-1111.tar", ref, used));
        assertEquals("Book-deadbeef_2.tar", ExportFilenames.readable("Book", "deadbeef-1111.tar", ref, used));
    }

    @Test
    public void readableWithNoExtensionSuggestion() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("calibre", "cafebabe-2222");
        assertEquals("Book-cafebabe", ExportFilenames.readable("Book", "cafebabe-2222", ref, used));
    }

    // ---- forItem policy (Kiwix keeps its native name; others are renamed) -----------------------

    @Test
    public void forItemKeepsKiwixNativeName() {
        Set<String> used = new HashSet<>();
        ItemRef ref = new ItemRef("kiwix", "wikipedia_en_all_2024.zim");
        String name = ExportFilenames.forItem("Wikipedia", "wikipedia_en_all_2024.zim", ref, used);
        assertEquals("wikipedia_en_all_2024.zim", name);
    }

    @Test
    public void forItemRenamesCalibreAndKolibri() {
        Set<String> used = new HashSet<>();
        assertEquals("Romeo-a79d2c9f.tar", ExportFilenames.forItem("Romeo",
                "a79d2c9f-0000.tar", new ItemRef("calibre", "a79d2c9f-0000"), used));
        assertEquals("Algebra-0123abcd.tar", ExportFilenames.forItem("Algebra",
                "0123abcd4567.tar", new ItemRef("kolibri", "0123abcd4567"), used));
    }
}
