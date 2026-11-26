package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The steps of the class age in the Join list. */
public class ClassAgeTest {

    private static String step(long sec) {
        ClassAge a = ClassAge.of(sec);
        return a == null ? null : a.toString();
    }

    private static long min(long m) { return m * 60; }

    @Test public void underOneMinuteIsJustNow() {
        assertEquals("JUST_NOW", step(0));
        assertEquals("JUST_NOW", step(59));
    }

    @Test public void eachMinuteFromOneToTen() {
        assertEquals("MINUTES 1", step(60));
        assertEquals("MINUTES 1", step(119));
        for (int m = 1; m <= 10; m++) assertEquals("MINUTES " + m, step(min(m)));
        assertEquals("MINUTES 10", step(min(11) - 1));
    }

    @Test public void thenAboutFifteenTwentyThirtyFortyFive() {
        assertEquals("ABOUT_MINUTES 15", step(min(11)));
        assertEquals("ABOUT_MINUTES 15", step(min(17)));
        assertEquals("ABOUT_MINUTES 20", step(min(18)));
        assertEquals("ABOUT_MINUTES 20", step(min(24)));
        assertEquals("ABOUT_MINUTES 30", step(min(25)));
        assertEquals("ABOUT_MINUTES 30", step(min(37)));
        assertEquals("ABOUT_MINUTES 45", step(min(38)));
        assertEquals("ABOUT_MINUTES 45", step(min(52)));
    }

    @Test public void thenHours() {
        assertEquals("ABOUT_ONE_HOUR", step(min(53)));
        assertEquals("ABOUT_ONE_HOUR", step(min(74)));
        assertEquals("ABOUT_HOUR_AND_HALF", step(min(75)));
        assertEquals("ABOUT_HOUR_AND_HALF", step(min(104)));
        assertEquals("ABOUT_TWO_HOURS", step(min(105)));
        assertEquals("ABOUT_TWO_HOURS", step(min(120) - 1));
        assertEquals("OVER_TWO_HOURS", step(min(120)));
        assertEquals("OVER_TWO_HOURS", step(Integer.MAX_VALUE));
    }

    @Test public void aNegativeAgeIsNotKnown() {
        assertNull(ClassAge.of(-1));
    }

    @Test public void theSameStepIsEqual() {
        assertEquals(ClassAge.of(min(12)), ClassAge.of(min(16)));
        assertEquals(ClassAge.of(min(12)).hashCode(), ClassAge.of(min(16)).hashCode());
    }
}
