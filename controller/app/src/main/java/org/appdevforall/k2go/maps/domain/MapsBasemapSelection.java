/*
 * ============================================================================
 * Name        : MapsBasemapSelection.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. The rule for which base-map layers a wizard selection
 *               delegates to dash-node. Pure JVM, no Android.
 * ============================================================================
 */
package org.appdevforall.k2go.maps.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Which base-map layers a wizard selection sends to dash-node (K2GO-394). The vector layer is always
 * downloaded; satellite and terrain are downloaded unless the selection is their OFF value. This is a
 * pure rule so the "which layers, skip which" decision has one testable home, not scattered inline in
 * the install service. The caller resolves each returned layer to a catalog file id (the download id).
 */
public final class MapsBasemapSelection {
    private MapsBasemapSelection() {}

    /** Satellite "off" -- the wizard's no-satellite value (the role drops the symlink for it). */
    public static final String SAT_OFF = "none";
    /** Terrain "off" -- the wizard's no-terrain value (the role's real off key). */
    public static final String TERRAIN_OFF = "0-none";

    /** A catalog layer to delegate: a group ("base" / "satellite" / "terrain") and a level. */
    public static final class Layer {
        public final String group;
        public final String level;

        public Layer(String group, String level) {
            this.group = group;
            this.level = level;
        }
    }

    /**
     * The catalog layers to pre-download through dash-node for this selection, in order, skipping any
     * layer that is off. "base" carries the vector level (the catalog group for the vector pmtiles).
     */
    public static List<Layer> layersToDelegate(String vector, String sat, String terrain) {
        List<Layer> out = new ArrayList<>(3);
        if (vector != null && !vector.isEmpty()) out.add(new Layer("base", vector));
        if (sat != null && !SAT_OFF.equals(sat)) out.add(new Layer("satellite", sat));
        if (terrain != null && !TERRAIN_OFF.equals(terrain)) out.add(new Layer("terrain", terrain));
        return out;
    }
}
