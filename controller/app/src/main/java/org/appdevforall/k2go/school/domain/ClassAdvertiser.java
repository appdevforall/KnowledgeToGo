/*
 * ============================================================================
 * Name        : ClassAdvertiser.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: port to put the class on the local network and take it off.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/** One network service registration at a time. Calls and results on the main thread. */
public interface ClassAdvertiser {

    interface Callback {
        /** A register or unregister ended. {@code registered}: the service is on the network. */
        void done(boolean registered);

        /** A register or unregister failed. */
        void failed();
    }

    /**
     * Starts the registration of the class with this code. {@code callback} also gets the result
     * of the next unregister.
     */
    void register(String code, Callback callback);

    /** Starts the unregistration of the registered service. */
    void unregister();
}
