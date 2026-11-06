/*
 * ============================================================================
 * Name        : WordCycle.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-553. Random word order for the "Preparing system" screen.
 * ============================================================================
 */
package org.appdevforall.k2go.boxidentity.presentation;

import java.util.Random;

/** Picks a random word index, never the same index twice in a row. Pure JVM. */
final class WordCycle {

    private final int count;
    private final Random random;
    private int last = -1;

    WordCycle(int count, Random random) {
        this.count = count;
        this.random = random;
    }

    /** The next index in [0, count), different from the previous one when count > 1. */
    int next() {
        if (count <= 1) {
            last = 0;
            return 0;
        }
        int i;
        if (last < 0) {
            i = random.nextInt(count);
        } else {
            // Pick among the other count-1 indexes, then skip over the last one.
            i = random.nextInt(count - 1);
            if (i >= last) i++;
        }
        last = i;
        return i;
    }
}
