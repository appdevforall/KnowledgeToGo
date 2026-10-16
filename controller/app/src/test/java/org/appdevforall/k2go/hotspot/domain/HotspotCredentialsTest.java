/*
 * ============================================================================
 * Name        : HotspotCredentialsTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-127. JVM unit tests for the pure hotspot credential rules.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.hotspot.domain.HotspotCredentials.Problem;
import org.junit.Test;

import java.util.Random;

public final class HotspotCredentialsTest {

    @Test public void defaultPassword_isValidWpa2() {
        assertEquals(Problem.NONE, HotspotCredentials.passwordProblem(HotspotCredentials.DEFAULT_PASSWORD));
        assertTrue(HotspotCredentials.DEFAULT_PASSWORD.length() >= HotspotCredentials.MIN_PASSWORD);
    }

    @Test public void name_keepsDirectPrefix() {
        assertTrue(HotspotCredentials.name("123").startsWith("DIRECT-"));
        assertEquals("DIRECT-K2GO-123", HotspotCredentials.name("123"));
    }

    @Test public void defaultSuffix_isThreeDigits_andDeterministicWithSeed() {
        String a = HotspotCredentials.defaultSuffix(new Random(42));
        String b = HotspotCredentials.defaultSuffix(new Random(42));
        assertEquals(HotspotCredentials.SUFFIX_DIGITS, a.length());
        assertEquals(a, b);                 // same seed -> same suffix (stable per device once persisted)
        assertTrue(a.chars().allMatch(Character::isDigit));
    }

    @Test public void nameProblem_flagsEmptyLongAndControl() {
        assertEquals(Problem.EMPTY, HotspotCredentials.nameProblem("   "));
        assertEquals(Problem.NONE, HotspotCredentials.nameProblem("Room1"));
        // PREFIX is 12 bytes; a 21-byte suffix pushes the whole name past 32.
        StringBuilder longSuffix = new StringBuilder();
        for (int i = 0; i < 21; i++) longSuffix.append('a');
        assertEquals(Problem.TOO_LONG, HotspotCredentials.nameProblem(longSuffix.toString()));
        assertEquals(Problem.CONTROL_CHAR, HotspotCredentials.nameProblem("Ro\u0007om"));
    }

    @Test public void passwordProblem_enforcesWpa2Bounds() {
        assertEquals(Problem.EMPTY, HotspotCredentials.passwordProblem(null));
        assertEquals(Problem.TOO_SHORT, HotspotCredentials.passwordProblem("short12"));   // 7 chars
        assertEquals(Problem.NONE, HotspotCredentials.passwordProblem("eightchr"));        // 8 chars
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < HotspotCredentials.MAX_PASSWORD + 1; i++) tooLong.append('x');
        assertEquals(Problem.TOO_LONG, HotspotCredentials.passwordProblem(tooLong.toString()));
        assertEquals(Problem.CONTROL_CHAR, HotspotCredentials.passwordProblem("pass\tword"));
    }

    @Test public void helpers_agreeWithProblem() {
        assertTrue(HotspotCredentials.isValidPassword(HotspotCredentials.DEFAULT_PASSWORD));
        assertFalse(HotspotCredentials.isValidPassword("short"));
        assertTrue(HotspotCredentials.isValidNameSuffix("Room1"));
        assertFalse(HotspotCredentials.isValidNameSuffix(""));
    }
}
