/*
 * ============================================================================
 * Name        : MapsBasemapSelectionTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. Unit tests for the base-map delegation rule.
 * ============================================================================
 */
package org.appdevforall.k2go.maps.domain;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class MapsBasemapSelectionTest {

    @Test
    public void allThreeLayersWhenNoneAreOff() {
        List<MapsBasemapSelection.Layer> layers =
                MapsBasemapSelection.layersToDelegate("14", "13", "10");
        assertEquals(3, layers.size());
        assertEquals("base", layers.get(0).group);
        assertEquals("14", layers.get(0).level);
        assertEquals("satellite", layers.get(1).group);
        assertEquals("13", layers.get(1).level);
        assertEquals("terrain", layers.get(2).group);
        assertEquals("10", layers.get(2).level);
    }

    @Test
    public void satelliteOffIsSkipped() {
        List<MapsBasemapSelection.Layer> layers =
                MapsBasemapSelection.layersToDelegate("nat-z8", MapsBasemapSelection.SAT_OFF, "7");
        assertEquals(2, layers.size());
        assertEquals("base", layers.get(0).group);
        assertEquals("terrain", layers.get(1).group);
    }

    @Test
    public void terrainOffIsSkipped() {
        List<MapsBasemapSelection.Layer> layers =
                MapsBasemapSelection.layersToDelegate("11", "9", MapsBasemapSelection.TERRAIN_OFF);
        assertEquals(2, layers.size());
        assertEquals("base", layers.get(0).group);
        assertEquals("satellite", layers.get(1).group);
    }

    @Test
    public void onlyVectorWhenBothOff() {
        List<MapsBasemapSelection.Layer> layers = MapsBasemapSelection.layersToDelegate(
                "nat-z8", MapsBasemapSelection.SAT_OFF, MapsBasemapSelection.TERRAIN_OFF);
        assertEquals(1, layers.size());
        assertEquals("base", layers.get(0).group);
        assertEquals("nat-z8", layers.get(0).level);
    }

    @Test
    public void emptyOrNullVectorIsSkipped() {
        assertEquals(0, MapsBasemapSelection.layersToDelegate("", "none", "0-none").size());
        assertEquals(0, MapsBasemapSelection.layersToDelegate(null, "none", "0-none").size());
    }
}
