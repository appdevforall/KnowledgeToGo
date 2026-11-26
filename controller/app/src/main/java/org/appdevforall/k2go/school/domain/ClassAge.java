/*
 * ============================================================================
 * Name        : ClassAge.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: how long ago a class started, in the steps of the class list.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

import java.util.Objects;

/**
 * The age of a class for the Join list: "just now", each minute from 1 to 10, then about 15, 20,
 * 30 and 45 minutes, about 1, 1.5 and 2 hours, then "more than 2 hours". A list needs no more
 * than this to tell a class that started now from one of the last lesson. The box sends the age
 * in seconds (its own clock); the clocks of offline phones do not agree, so no time of day.
 */
public final class ClassAge {

    public enum Kind { JUST_NOW, MINUTES, ABOUT_MINUTES, ABOUT_ONE_HOUR, ABOUT_HOUR_AND_HALF, ABOUT_TWO_HOURS, OVER_TWO_HOURS }

    public final Kind kind;
    /** The minutes for {@link Kind#MINUTES} (1 to 10) and {@link Kind#ABOUT_MINUTES} (15, 20, 30, 45); else 0. */
    public final int minutes;

    private ClassAge(Kind kind, int minutes) {
        this.kind = kind;
        this.minutes = minutes;
    }

    /** The step for an age in seconds, or null when the age is not known (a negative value). */
    public static ClassAge of(long ageSec) {
        if (ageSec < 0) return null;
        long m = ageSec / 60;
        if (m < 1) return new ClassAge(Kind.JUST_NOW, 0);
        if (m <= 10) return new ClassAge(Kind.MINUTES, (int) m);
        // From 11 minutes on: about 15 up to 17 minutes, then the nearest step. "More than two
        // hours" from 120 minutes on.
        if (m <= 17) return new ClassAge(Kind.ABOUT_MINUTES, 15);
        if (m <= 24) return new ClassAge(Kind.ABOUT_MINUTES, 20);
        if (m <= 37) return new ClassAge(Kind.ABOUT_MINUTES, 30);
        if (m <= 52) return new ClassAge(Kind.ABOUT_MINUTES, 45);
        if (m <= 74) return new ClassAge(Kind.ABOUT_ONE_HOUR, 0);
        if (m <= 104) return new ClassAge(Kind.ABOUT_HOUR_AND_HALF, 0);
        if (m < 120) return new ClassAge(Kind.ABOUT_TWO_HOURS, 0);
        return new ClassAge(Kind.OVER_TWO_HOURS, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ClassAge)) return false;
        ClassAge a = (ClassAge) o;
        return kind == a.kind && minutes == a.minutes;
    }

    @Override
    public int hashCode() { return Objects.hash(kind, minutes); }

    @Override
    public String toString() { return kind + (minutes > 0 ? " " + minutes : ""); }
}
