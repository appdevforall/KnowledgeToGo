package org.iiab.controller.install.domain;

/**
 * ADFA-5228: a rounded, calm ETA for display. Pure — it carries the SHAPE (unknown / under a minute
 * / N minutes), not the words, so the UI supplies localized strings and this stays JVM-testable.
 * Rounding to whole minutes keeps the label from flickering second by second.
 */
public final class Eta {

    public enum Kind { UNKNOWN, UNDER_MINUTE, MINUTES }

    public final Kind kind;
    /** Whole minutes; meaningful only when {@link #kind} is {@link Kind#MINUTES}. */
    public final int minutes;

    private Eta(Kind kind, int minutes) {
        this.kind = kind;
        this.minutes = minutes;
    }

    /** @param seconds estimated seconds remaining, or negative for unknown. */
    public static Eta of(long seconds) {
        if (seconds < 0L) return new Eta(Kind.UNKNOWN, 0);
        if (seconds < 60L) return new Eta(Kind.UNDER_MINUTE, 0);
        int mins = (int) Math.round(seconds / 60.0);
        if (mins < 1) mins = 1;
        return new Eta(Kind.MINUTES, mins);
    }
}
