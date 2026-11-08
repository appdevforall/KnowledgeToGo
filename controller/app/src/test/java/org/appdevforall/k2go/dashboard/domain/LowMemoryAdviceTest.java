package org.appdevforall.k2go.dashboard.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the low-memory advisory rule (K2GO-557). */
public class LowMemoryAdviceTest {

    private static final long MB = 1024L * 1024L;

    @Test public void comfortableHeadroom_noAdvice() {
        // About 700 MB free and not low: clear headroom, so leave the user alone.
        assertFalse(LowMemoryAdvice.shouldAdviseCloseApps(new DeviceMemory(700 * MB, 1400 * MB, false)));
    }

    @Test public void exactlyAtNeed_advises() {
        assertTrue(LowMemoryAdvice.shouldAdviseCloseApps(
                new DeviceMemory(LowMemoryAdvice.UPDATE_WORKING_SET_BYTES, 1400 * MB, false)));
    }

    @Test public void belowNeed_advises() {
        assertTrue(LowMemoryAdvice.shouldAdviseCloseApps(new DeviceMemory(200 * MB, 1400 * MB, false)));
    }

    @Test public void systemLowMemory_advisesEvenWithHeadroom() {
        assertTrue(LowMemoryAdvice.shouldAdviseCloseApps(new DeviceMemory(800 * MB, 1400 * MB, true)));
    }

    @Test public void nullSnapshot_noAdvice() {
        assertFalse(LowMemoryAdvice.shouldAdviseCloseApps(null));
    }
}
