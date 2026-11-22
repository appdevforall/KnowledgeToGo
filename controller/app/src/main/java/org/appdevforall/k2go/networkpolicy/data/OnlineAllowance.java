/*
 * ============================================================================
 * Name        : OnlineAllowance.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-588. May a saved order of one kind connect to the internet now?
 * ============================================================================
 */
package org.appdevforall.k2go.networkpolicy.data;

import android.content.Context;

import org.appdevforall.k2go.delivery.data.OnlineConsent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K2GO-588: a saved order (a wishlist of ZIM, books, courses, maps or modules) connects only when
 * the privacy choice is on, or when the user allowed that kind of order in this process ("Allow only
 * this time" at its commit point). With Keep offline and no allow, the order stays saved and waits;
 * Pending downloads still lists it. The allow lives in memory only, like the metered session
 * consent (SessionMeteredConsentStore): a new process asks again, so it never outlives the session
 * the user allowed.
 */
public final class OnlineAllowance {

    /** The kind key for module installs; the content kinds use {@code ContentType.key()}. */
    public static final String MODULES = "modules";

    private static final Set<String> ALLOWED = ConcurrentHashMap.newKeySet();

    private OnlineAllowance() {}

    /** The user allowed this kind of order at its commit point. */
    public static void allow(String kind) {
        ALLOWED.add(kind);
    }

    public static boolean allows(Context ctx, String kind) {
        return OnlineConsent.isEnabled(ctx) || ALLOWED.contains(kind);
    }
}
