/*
 * ============================================================================
 * Name        : WordCycleTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-549. Unit tests for WordCycle.
 * ============================================================================
 */
package org.appdevforall.k2go.boxidentity.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public class WordCycleTest {

    @Test
    public void neverRepeatsTheLastWordAndReachesAllWords() {
        WordCycle c = new WordCycle(8, new Random(42));
        Set<Integer> seen = new HashSet<>();
        int prev = c.next();
        seen.add(prev);
        for (int k = 0; k < 2000; k++) {
            int i = c.next();
            assertTrue(i >= 0 && i < 8);
            assertNotEquals(prev, i);
            seen.add(i);
            prev = i;
        }
        assertEquals(8, seen.size());
    }

    @Test
    public void singleWordIsAlwaysZero() {
        WordCycle c = new WordCycle(1, new Random(1));
        assertEquals(0, c.next());
        assertEquals(0, c.next());
    }
}
