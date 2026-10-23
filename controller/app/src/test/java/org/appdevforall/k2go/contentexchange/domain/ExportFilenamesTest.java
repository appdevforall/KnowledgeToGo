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
}
