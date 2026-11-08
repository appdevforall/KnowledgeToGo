/*
 * ============================================================================
 * Name        : HotspotHoldersTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-561. JVM unit tests for HotspotHolders.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.hotspot.domain.HotspotHolders.Holder;
import org.junit.Test;

public final class HotspotHoldersTest {

    @Test public void soleHolderRelease_stops() {
        HotspotHolders h = new HotspotHolders();
        h.add(Holder.CLONE);
        assertTrue(h.releaseLast(Holder.CLONE));
        assertTrue(h.isEmpty());
    }

    @Test public void releaseWhileAnotherHolds_keepsItUp() {
        HotspotHolders h = new HotspotHolders();
        h.add(Holder.CONNECT);
        h.add(Holder.CLONE);
        assertFalse(h.releaseLast(Holder.CLONE));
        assertTrue(h.releaseLast(Holder.CONNECT));
    }

    @Test public void releaseOfNonHolder_neverStops() {
        HotspotHolders h = new HotspotHolders();
        assertFalse(h.releaseLast(Holder.CLONE));   // Clone never started it: do not stop another's
        h.add(Holder.CONNECT);
        assertFalse(h.releaseLast(Holder.CLONE));
    }

    @Test public void addIsIdempotent() {
        HotspotHolders h = new HotspotHolders();
        h.add(Holder.CLONE);
        h.add(Holder.CLONE);   // ensureHotspot runs on every render
        assertTrue(h.releaseLast(Holder.CLONE));
    }

    @Test public void clear_dropsEveryHolder() {
        HotspotHolders h = new HotspotHolders();
        h.add(Holder.CONNECT);
        h.add(Holder.CONTENT_SHARE);
        h.clear();
        assertTrue(h.isEmpty());
        assertFalse(h.releaseLast(Holder.CONNECT));
    }
}
