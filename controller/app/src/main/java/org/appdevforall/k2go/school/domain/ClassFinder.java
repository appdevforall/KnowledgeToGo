/*
 * ============================================================================
 * Name        : ClassFinder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: port to find the open classes on the local network.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/** Finds the classes that teacher boxes announce. Calls and results on the main thread. */
public interface ClassFinder {

    interface Listener {
        void onFound(FoundClass found);

        /** The class with this {@link FoundClass#key} left the network. */
        void onLost(String key);
    }

    /** Starts to look. A second start before a stop does nothing. */
    void start(Listener listener);

    /** Stops to look. No result comes after it. */
    void stop();
}
