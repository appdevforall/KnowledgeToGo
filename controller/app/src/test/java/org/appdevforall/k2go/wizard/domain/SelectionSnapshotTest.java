package org.appdevforall.k2go.wizard.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Unit tests for {@link SelectionSnapshot} — the flattening that lets the wizard's
 * carts survive a process death. Pure JVM: this is the half of saved state that can be
 * got wrong silently, so it is the half that is tested.
 */
public class SelectionSnapshotTest {

    private static LinkedHashMap<String, Long> zim() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("wikipedia|en|maxi", 4_000L);
        m.put("wiktionary|es|nopic", 900L);
        return m;
    }

    private static LinkedHashMap<String, String[]> books() {
        LinkedHashMap<String, String[]> m = new LinkedHashMap<>();
        m.put("1342", new String[]{"Pride and Prejudice", "Austen", "http://a"});
        m.put("84", new String[]{"Frankenstein", "Shelley", "http://b"});
        return m;
    }

    // ---- round trips ---------------------------------------------------------

    @Test
    public void aZimCartComesBackWhole() {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        assertTrue(SelectionSnapshot.restoreZim(out,
                SelectionSnapshot.keys(zim()), SelectionSnapshot.sizes(zim())));
        assertEquals(zim(), out);
    }

    @Test
    public void aBooksCartComesBackWhole() {
        LinkedHashMap<String, String[]> src = books();
        LinkedHashMap<String, String[]> out = new LinkedHashMap<>();
        assertTrue(SelectionSnapshot.restoreBooks(out,
                SelectionSnapshot.keys(src),
                SelectionSnapshot.bookColumn(src, 0),
                SelectionSnapshot.bookColumn(src, 1),
                SelectionSnapshot.bookColumn(src, 2)));

        assertEquals(src.keySet(), out.keySet());
        assertArrayEquals(src.get("1342"), out.get("1342"));
        assertArrayEquals(src.get("84"), out.get("84"));
    }

    @Test
    public void theOrderThePicksWereMadeInIsKept() {
        // Both review screens list entries in pick order, so a round trip that reorders
        // them would quietly rewrite what the user sees.
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        SelectionSnapshot.restoreZim(out,
                SelectionSnapshot.keys(zim()), SelectionSnapshot.sizes(zim()));
        assertEquals(new ArrayList<>(zim().keySet()), new ArrayList<>(out.keySet()));
    }

    // ---- fails closed --------------------------------------------------------

    @Test
    public void arraysThatDoNotAgreeRestoreNothing() {
        // Half a cart is worse than none: a title attached to the wrong book is a lie
        // the user cannot spot.
        LinkedHashMap<String, Long> z = new LinkedHashMap<>();
        assertFalse(SelectionSnapshot.restoreZim(z,
                new String[]{"a", "b"}, new long[]{1L}));
        assertTrue(z.isEmpty());

        LinkedHashMap<String, String[]> b = new LinkedHashMap<>();
        assertFalse(SelectionSnapshot.restoreBooks(b,
                new String[]{"1", "2"}, new String[]{"t1", "t2"},
                new String[]{"a1"}, new String[]{"u1", "u2"}));
        assertTrue(b.isEmpty());
    }

    @Test
    public void nothingSavedRestoresNothingRatherThanThrowing() {
        assertFalse(SelectionSnapshot.restoreZim(new LinkedHashMap<>(), null, null));
        assertFalse(SelectionSnapshot.restoreBooks(new LinkedHashMap<>(),
                null, null, null, null));
    }

    // ---- ragged input --------------------------------------------------------

    @Test
    public void aShortOrNullValueBecomesEmptyStringsSoTheColumnsStayAligned() {
        // The Books cart is filled by another screen; a value that is not the expected
        // {title, author, url} must not shift every later entry by one.
        LinkedHashMap<String, String[]> src = new LinkedHashMap<>();
        src.put("1", new String[]{"only a title"});
        src.put("2", null);
        src.put("3", new String[]{"t", "a", "u"});

        assertArrayEquals(new String[]{"only a title", "", "t"},
                SelectionSnapshot.bookColumn(src, 0));
        assertArrayEquals(new String[]{"", "", "a"}, SelectionSnapshot.bookColumn(src, 1));
        assertArrayEquals(new String[]{"", "", "u"}, SelectionSnapshot.bookColumn(src, 2));
        assertEquals(3, SelectionSnapshot.keys(src).length);
    }

    @Test
    public void aNullSizeCountsAsZeroRatherThanBreakingTheRoundTrip() {
        LinkedHashMap<String, Long> src = new LinkedHashMap<>();
        src.put("wikipedia|en|maxi", null);
        assertArrayEquals(new long[]{0L}, SelectionSnapshot.sizes(src));
    }

    @Test
    public void anEmptyCartIsAnEmptyRoundTripNotAFailure() {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        assertTrue(SelectionSnapshot.restoreZim(out,
                SelectionSnapshot.keys(new LinkedHashMap<String, Long>()),
                SelectionSnapshot.sizes(new LinkedHashMap<String, Long>())));
        assertTrue(out.isEmpty());
    }
}
