/*
 * ============================================================================
 * Name        : MapsRegionClientTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-583. An overlap answer from the box is told apart from a network failure.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapsRegionClientTest {

    @Test
    public void boxOverlapAnswerIsAnOverlap() {
        assertTrue(MapsRegionClient.isOverlap("HTTP 409: {\"error\":\"overlaps an existing region\"}"));
        assertTrue(MapsRegionClient.isOverlap("error: you cannot download OVERLAPPING full quality regions"));
    }

    @Test
    public void networkAndOtherFailuresAreNotAnOverlap() {
        assertFalse(MapsRegionClient.isOverlap(null));
        assertFalse(MapsRegionClient.isOverlap("failed to connect to /127.0.0.1 (port 8085)"));
        assertFalse(MapsRegionClient.isOverlap("HTTP 504: {\"error\":\"estimate timed out\"}"));
        assertFalse(MapsRegionClient.isOverlap("HTTP 500: {\"error\":\"Error: spawn sudo ENOENT\"}"));
        assertFalse(MapsRegionClient.isOverlap("timeout after 4090 ms"));
        // A 409 that is not an overlap (a busy lock) must keep the Retry path.
        assertFalse(MapsRegionClient.isOverlap("HTTP 409: {\"error\":\"another map region download or delete is running\",\"busy\":true}"));
    }
}
