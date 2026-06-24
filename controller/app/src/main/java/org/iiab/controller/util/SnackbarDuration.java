/*
 * ============================================================================
 * Name        : SnackbarDuration.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reading-time based Snackbar duration (longer text shows longer).
 * ============================================================================
 */
package org.iiab.controller.util;

/**
 * Snackbar has only SHORT (~1.5s) / LONG (~2.75s). Long messages get cut off.
 * This scales the duration with the message length using an average reading
 * speed (~200 wpm ≈ 300ms/word) plus a reaction margin, clamped so it never
 * flashes too briefly nor lingers forever. Pure JVM (unit-tested).
 */
public final class SnackbarDuration {

    private SnackbarDuration() {}

    static final int BASE_MS = 1500;       // fixed reaction/append time
    static final int PER_WORD_MS = 320;    // ~200 wpm reading speed + margin
    static final int MIN_MS = 3000;        // floor (~ LENGTH_LONG)
    static final int MAX_MS = 10000;       // cap so it doesn't linger

    /** Duration in ms appropriate for reading {@code text}. */
    public static int millisForText(String text) {
        if (text == null) return MIN_MS;
        String t = text.trim();
        if (t.isEmpty()) return MIN_MS;
        int words = t.split("\\s+").length;
        long ms = BASE_MS + (long) words * PER_WORD_MS;
        if (ms < MIN_MS) ms = MIN_MS;
        if (ms > MAX_MS) ms = MAX_MS;
        return (int) ms;
    }
}
