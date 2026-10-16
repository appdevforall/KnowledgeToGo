/*
 * ============================================================================
 * Name        : HotspotBandTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-127. JVM unit tests for the pure HotspotBand enum.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public final class HotspotBandTest {

    @Test public void default_isTwoGhz() {
        assertSame(HotspotBand.TWO_GHZ, HotspotBand.DEFAULT);
    }

    @Test public void fromId_roundTripsEveryValue() {
        for (HotspotBand b : HotspotBand.values()) {
            assertSame(b, HotspotBand.fromId(b.id));
        }
    }

    @Test public void fromId_unknownFallsBackToDefault() {
        assertSame(HotspotBand.DEFAULT, HotspotBand.fromId(99));
        assertSame(HotspotBand.DEFAULT, HotspotBand.fromId(-1));
    }

    @Test public void ids_areStable() {
        assertEquals(0, HotspotBand.TWO_GHZ.id);
        assertEquals(1, HotspotBand.FIVE_GHZ.id);
        assertEquals(2, HotspotBand.AUTO.id);
    }
}
