package org.iiab.controller.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link Channel}. Pure JVM, no Android dependencies.
 */
public class ChannelTest {

    private static final String KHAN = "95a52b386f2c485cb97dd60901674a98";
    private static final String ROOT = "aaaa1111bbbb2222cccc3333dddd4444";

    @Test
    public void carriesWhatThePickerNeeds() {
        Channel c = Channel.builder(KHAN).version(12).name("Khan Academy")
                .description("maths and more").author("KA")
                .language("en", "English").totalResources(50000)
                .publishedSize(9_876_543_210L).rootNodeId(ROOT).build();
        assertEquals(KHAN, c.id());
        assertEquals(12, c.version());
        assertEquals("Khan Academy", c.name());
        assertEquals("KA", c.author());
        assertEquals("English", c.langName());
        assertEquals(50000, c.totalResources());
        assertEquals(9_876_543_210L, c.publishedSize());
        assertEquals(ROOT, c.rootNodeId());
        assertTrue(c.hasKnownSize());
    }

    @Test
    public void anUnusableIdYieldsNullRatherThanAHalfBuiltChannel() {
        // One malformed row in a catalog of hundreds should cost that row, not
        // abort the parse, so the builder returns null instead of throwing.
        assertNull(Channel.builder("not-an-id").name("x").build());
        assertNull(Channel.builder("").build());
        assertNull(Channel.builder(null).build());
    }

    @Test
    public void theIdIsNormalised() {
        assertEquals(KHAN, Channel.builder("95A52B38-6F2C-485C-B97D-D60901674A98")
                .build().id());
    }

    @Test
    public void anAbsentRootFallsBackToTheChannelId() {
        assertEquals(KHAN, Channel.builder(KHAN).build().rootNodeId());
        assertEquals(KHAN, Channel.builder(KHAN).rootNodeId("junk").build().rootNodeId());
    }

    @Test
    public void nullTextBecomesEmptyNotNull() {
        Channel c = Channel.builder(KHAN).name(null).description(null)
                .author(null).language(null, null).build();
        assertEquals("", c.name());
        assertEquals("", c.description());
        assertEquals("", c.author());
        assertEquals("", c.langCode());
    }

    @Test
    public void negativeNumbersAreClamped() {
        Channel c = Channel.builder(KHAN).version(-2).totalResources(-5)
                .publishedSize(-100L).build();
        assertEquals(0, c.version());
        assertEquals(0, c.totalResources());
        assertEquals(0L, c.publishedSize());
        assertFalse(c.hasKnownSize());
    }

    @Test
    public void aZeroSizeCountsAsUnknown() {
        // Studio reports 0 for a channel published with no resources yet; the
        // picker must not present that as "this download is free".
        assertFalse(Channel.builder(KHAN).publishedSize(0L).build().hasKnownSize());
    }

    @Test
    public void identityIsTheIdAndVersionTogether() {
        // A channel is only fully specified by the pair: the published size
        // belongs to that version, not to the id.
        assertEquals(Channel.builder(KHAN).version(3).build(),
                Channel.builder(KHAN).version(3).name("different label").build());
        assertNotEquals(Channel.builder(KHAN).version(3).build(),
                Channel.builder(KHAN).version(4).build());
        assertEquals(Channel.builder(KHAN).version(3).build().hashCode(),
                Channel.builder(KHAN.toUpperCase()).version(3).build().hashCode());
    }

    @Test
    public void whitespaceIsTrimmedFromText() {
        Channel c = Channel.builder(KHAN).name("  Khan Academy  ")
                .language("  en  ", "  English  ").build();
        assertEquals("Khan Academy", c.name());
        assertEquals("en", c.langCode());
        assertEquals("English", c.langName());
    }
}
