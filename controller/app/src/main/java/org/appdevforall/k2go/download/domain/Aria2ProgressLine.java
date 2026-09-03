/*
 * ============================================================================
 * Name        : Aria2ProgressLine.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4895. Pure rule: pull the figures out of one line of
 *               aria2 console output. No Android, no I/O.
 * ============================================================================
 */
package org.appdevforall.k2go.download.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one line of aria2's summary output.
 *
 * <p>A real line looks like this:
 *
 * <pre>[#2089b0 400MiB/1.0GiB(39%) CN:4 DL:4.5MiB ETA:2m20s]</pre>
 *
 * <p><b>Why this is its own class.</b> It began as a second {@link Pattern} inside
 * {@code Aria2Manager}, which is an Android class holding a live process and a Handler — so the
 * most breakable part of the change, a regex against a third party's output format, was the one
 * part no test could reach. Here it is reachable, and a change to aria2's formatting fails a test
 * instead of quietly producing a wrong estimate on a device.
 *
 * <p><b>Deliberately separate from the progress pattern that already worked.</b> The percentage
 * and rate the screen has always shown come from a different expression in {@code Aria2Manager}.
 * If this one stops matching, the estimate goes unknown and the line the user reads is untouched.
 */
public final class Aria2ProgressLine {

    /**
     * The completed/total pair that precedes the percentage.
     *
     * <p>Both halves are a number with an optional unit, so {@code 400MiB/1.0GiB(39%)} yields
     * {@code 400MiB} and {@code 1.0GiB}. Anchored on the {@code (NN%)} that follows, which is what
     * keeps it from matching the {@code CN:4} or a path with a slash in it.
     */
    private static final Pattern SIZES =
            Pattern.compile("([\\d.]+[A-Za-z]*)/([\\d.]+[A-Za-z]*)\\(\\d+%\\)");

    private Aria2ProgressLine() {
    }

    /** Bytes transferred so far, or {@link ByteToken#UNKNOWN} if the line does not carry them. */
    public static long completedBytes(String line) {
        Matcher m = matcher(line);
        return m == null ? ByteToken.UNKNOWN : ByteToken.parse(m.group(1));
    }

    /**
     * The size aria2 believes it is fetching, or {@link ByteToken#UNKNOWN}.
     *
     * <p>Prefer the Metalink's figure over this one where both exist: the Metalink is what the
     * integrity gate checks against, so using it keeps the estimate and the verdict measured
     * against the same number.
     */
    public static long declaredTotalBytes(String line) {
        Matcher m = matcher(line);
        return m == null ? ByteToken.UNKNOWN : ByteToken.parse(m.group(2));
    }

    private static Matcher matcher(String line) {
        if (line == null || line.isEmpty()) return null;
        Matcher m = SIZES.matcher(line);
        return m.find() ? m : null;
    }
}
