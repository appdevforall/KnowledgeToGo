/*
 * ============================================================================
 * Name        : HotspotHolders.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-561. The one record of which features use the shared hotspot. The last
 *               release stops it, so one feature never stops a hotspot another still uses.
 *               Pure JVM (no Android). Thread-safe.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

import java.util.EnumSet;

public final class HotspotHolders {

    /** Features that keep the shared hotspot up. */
    public enum Holder { CONNECT, CLONE, CONTENT_SHARE }

    private final EnumSet<Holder> held = EnumSet.noneOf(Holder.class);

    public synchronized void add(Holder holder) { held.add(holder); }

    /** Removes [holder]. True only when it was held and no other holder is left: stop the hotspot. */
    public synchronized boolean releaseLast(Holder holder) {
        return held.remove(holder) && held.isEmpty();
    }

    /** The hotspot went down for everyone. */
    public synchronized void clear() { held.clear(); }

    public synchronized boolean isEmpty() { return held.isEmpty(); }
}
