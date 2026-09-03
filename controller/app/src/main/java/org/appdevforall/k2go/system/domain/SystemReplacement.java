/*
 * ============================================================================
 * Name        : SystemReplacement.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The rule for what a replaced system invalidates. Pure JVM, no
 *               Android (ADFA-5070).
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * When the system is replaced, what was about it stops being true.
 *
 * <p>Content state is scoped to a rootfs but stored in the app: the download
 * sessions live in memory for as long as the process does, and the orders live on
 * disk indefinitely. Neither notices when the rootfs underneath is wiped, so after
 * a reinstall the app goes on describing a system that no longer exists — a
 * finished download reported against an empty content database, and a session still
 * counted as open, which then refuses every later download as "another one is
 * running".
 *
 * <p>This is the {@code SYSTEM} operation of ADR-5061 acting on content state:
 * the class is a property of the operation, and one of its properties is what it
 * destroys.
 *
 * <p><b>Sessions always go. Orders usually.</b> A session describes work against a
 * system that is gone, so it is never worth keeping. An order — a wishlist — is
 * different, because the wizard writes one <em>immediately before</em> installing:
 * clearing orders on that path would throw away the very thing the user just asked
 * for. Every other route reaches destruction without a wizard, so any order it
 * finds was placed against the system being destroyed.
 *
 * <p>Pure: the rule is decided here and carried out by the Android side, so it can
 * be read and tested in one place.
 */
public final class SystemReplacement {

    /** How the system came to be replaced. Each route declares itself. */
    public enum Cause {
        /**
         * The wizard's reinstall: wipe, then install, then drain the orders the user
         * placed on the way in. The one route that must keep them.
         */
        REINSTALL,
        /** Reset to a clean system, from the legacy Advanced screen. No wizard. */
        RESET,
        /** A backup extracted over the rootfs. The content that arrives is the backup's. */
        RESTORE,
        /** A rootfs received from another device over the wire. */
        CLONE_RECEIVE,
        /**
         * The rootfs deleted outright, leaving no system at all.
         *
         * <p>Its only route — the legacy Advanced screen's fast delete — is switched
         * off (ADFA-5070). The cause stays because the code behind that flag is
         * wired to it: turning the flag back on must not also bring back the stale
         * state the route used to leave.
         */
        DELETE,
        /**
         * ADFA-5119: the user abandoned the install before the system existed. Nothing is replaced —
         * the system is simply never created — but the consequence for content state is identical,
         * and it is the reason this cause is not {@link #REINSTALL}'s twin.
         *
         * <p>A reinstall keeps the orders because the user filled them on the way in for the system
         * about to be built. Here that system is exactly what is being given up, so the orders go
         * with it: keeping them would drain a wishlist chosen for one tier into whatever the user
         * picks next, which is the stale-wishlist bug ADFA-4874 fixed at the other end of the same
         * flow.
         */
        ABANDONED_INSTALL
    }

    private SystemReplacement() {
    }

    /**
     * Whether this route should also discard the pending orders.
     *
     * <p>False only for {@link Cause#REINSTALL}: the wizard clears the wishlists when
     * it opens and the user then fills them again, so by the time the install starts
     * the orders are for the system about to be created, not the one being replaced.
     */
    public static boolean clearsOrders(Cause cause) {
        return cause != null && cause != Cause.REINSTALL;
    }

    /**
     * Whether this route should discard the in-flight download sessions. Always —
     * kept as a method so the two questions read alike at the call site, and so a
     * future exception has somewhere to live rather than being written inline.
     */
    public static boolean clearsSessions(Cause cause) {
        return cause != null;
    }
}
