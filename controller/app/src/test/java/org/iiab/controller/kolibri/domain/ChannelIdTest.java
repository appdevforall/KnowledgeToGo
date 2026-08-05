package org.iiab.controller.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ChannelId} — the guard that stops a channel token, a
 * dashed UUID or plain junk from reaching a request as if it were an id.
 * Pure JVM, no Android dependencies.
 */
public class ChannelIdTest {

    /** African Storybook Library, as Kolibri stores it. */
    private static final String CANONICAL = "f9d3e0e46ea25789bbed672ff6a399ed";

    @Test
    public void acceptsACanonicalId() {
        assertEquals(CANONICAL, ChannelId.normalise(CANONICAL));
        assertTrue(ChannelId.isValid(CANONICAL));
        assertTrue(ChannelId.isCanonical(CANONICAL));
    }

    @Test
    public void stripsDashesFromAUuidCopiedFromAUrl() {
        assertEquals(CANONICAL, ChannelId.normalise("f9d3e0e4-6ea2-5789-bbed-672ff6a399ed"));
    }

    @Test
    public void foldsUppercaseHex() {
        assertEquals(CANONICAL, ChannelId.normalise(CANONICAL.toUpperCase()));
    }

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals(CANONICAL, ChannelId.normalise("  " + CANONICAL + "\n"));
    }

    @Test
    public void rejectsAChannelTokenBecauseItIsNotAnId() {
        // The trap this class exists for: a token passed as an id ends in a 404
        // from the downloader with no useful message. It has to be resolved first.
        assertNull(ChannelId.normalise("bisan-sukod"));
        assertNull(ChannelId.normalise("bisansukod"));
        assertFalse(ChannelId.isValid("bisan-sukod"));
    }

    @Test
    public void rejectsWrongLength() {
        assertNull(ChannelId.normalise(CANONICAL.substring(0, 31)));
        assertNull(ChannelId.normalise(CANONICAL + "a"));
    }

    @Test
    public void rejectsNonHexEvenAtTheRightLength() {
        assertNull(ChannelId.normalise("z9d3e0e46ea25789bbed672ff6a399ed"));
        // 32 chars of dashes collapse to nothing, not to a valid id.
        assertNull(ChannelId.normalise("--------------------------------"));
    }

    @Test
    public void rejectsNullAndEmpty() {
        assertNull(ChannelId.normalise(null));
        assertNull(ChannelId.normalise(""));
        assertNull(ChannelId.normalise("   "));
        assertFalse(ChannelId.isValid(null));
    }

    @Test
    public void rejectsInjectionAttempts() {
        // An id reaches a request the box acts on, so anything with structure in
        // it is rejected outright rather than escaped downstream.
        assertNull(ChannelId.normalise(CANONICAL + "; rm -rf /"));
        assertNull(ChannelId.normalise("../../etc/passwd"));
        assertNull(ChannelId.normalise(CANONICAL.substring(0, 30) + "$("));
    }

    @Test
    public void isCanonicalDoesNotNormaliseSilently() {
        // isCanonical asserts the value is already clean; it must not accept the
        // dashed or uppercase spellings that normalise() would happily convert.
        assertFalse(ChannelId.isCanonical("f9d3e0e4-6ea2-5789-bbed-672ff6a399ed"));
        assertFalse(ChannelId.isCanonical(CANONICAL.toUpperCase()));
        assertFalse(ChannelId.isCanonical(null));
    }

    @Test
    public void recognisesTokensSoTheErrorCanSayWhy() {
        assertTrue(ChannelId.looksLikeToken("bisan-sukod"));
        assertTrue(ChannelId.looksLikeToken("bisansukod"));
        assertTrue(ChannelId.looksLikeToken("BISAN-SUKOD"));
        assertTrue(ChannelId.looksLikeToken("  bisansukod  "));
    }

    @Test
    public void doesNotMistakeAnIdForAToken() {
        assertFalse(ChannelId.looksLikeToken(CANONICAL));
        assertFalse(ChannelId.looksLikeToken(null));
        assertFalse(ChannelId.looksLikeToken("too-short"));
        assertFalse(ChannelId.looksLikeToken("has spaces"));
        assertFalse(ChannelId.looksLikeToken("bisan_sukod"));
    }
}
