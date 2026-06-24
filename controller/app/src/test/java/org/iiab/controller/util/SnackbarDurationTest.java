package org.iiab.controller.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for reading-time Snackbar duration. */
public class SnackbarDurationTest {

    @Test public void nullOrBlankIsFloor() {
        assertEquals(SnackbarDuration.MIN_MS, SnackbarDuration.millisForText(null));
        assertEquals(SnackbarDuration.MIN_MS, SnackbarDuration.millisForText("   "));
    }

    @Test public void shortTextStaysAtFloor() {
        assertEquals(SnackbarDuration.MIN_MS, SnackbarDuration.millisForText("Done"));
    }

    @Test public void longerTextScalesUp() {
        int many = SnackbarDuration.millisForText(
                "This backup was built for a different processor architecture and cannot run on this device");
        assertTrue("expected > floor, got " + many, many > SnackbarDuration.MIN_MS);
        assertTrue(many <= SnackbarDuration.MAX_MS);
    }

    @Test public void veryLongTextIsCapped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("word ");
        assertEquals(SnackbarDuration.MAX_MS, SnackbarDuration.millisForText(sb.toString()));
    }
}
