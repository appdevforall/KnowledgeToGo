package org.iiab.controller.system.domain;

import static org.iiab.controller.system.domain.SystemReplacement.Cause;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SystemReplacement} — which routes discard the pending
 * orders and which one must not. Pure JVM.
 */
public class SystemReplacementTest {

    @Test
    public void everyRouteDiscardsTheSessions() {
        // A session describes work against a system that is gone. There is no route
        // where keeping one is useful, and keeping one is what refuses every later
        // download as "another one is running".
        for (Cause c : Cause.values()) {
            assertTrue(c.name(), SystemReplacement.clearsSessions(c));
        }
    }

    @Test
    public void theWizardsReinstallKeepsTheOrders() {
        // The one exception, and the reason the rule needs a type at all: the wizard
        // clears the wishlists when it opens and the user refills them on the way in,
        // so by the time the wipe starts the orders are for the system being created.
        assertFalse(SystemReplacement.clearsOrders(Cause.REINSTALL));
    }

    @Test
    public void everyOtherRouteDiscardsTheOrders() {
        // None of these runs a wizard, so an order found here was placed against the
        // system being destroyed.
        assertTrue(SystemReplacement.clearsOrders(Cause.RESET));
        assertTrue(SystemReplacement.clearsOrders(Cause.RESTORE));
        assertTrue(SystemReplacement.clearsOrders(Cause.CLONE_RECEIVE));
        assertTrue(SystemReplacement.clearsOrders(Cause.DELETE));
        // ADFA-5119. This one arrives from inside the wizard, which is what makes it worth its own
        // line: it looks like REINSTALL's twin and behaves like its opposite. A reinstall keeps the
        // orders because the user filled them for the system about to be built; an abandonment is
        // the user giving that system up, so the orders go with it — otherwise a wishlist chosen for
        // one tier drains into whatever they pick next.
        assertTrue(SystemReplacement.clearsOrders(Cause.ABANDONED_INSTALL));
    }

    @Test
    public void everyCauseIsAccountedFor() {
        // Guards the enum against growing a value that nobody decided the rule for:
        // a new route added without a decision would otherwise silently keep orders.
        for (Cause c : Cause.values()) {
            assertTrue(c.name(),
                    SystemReplacement.clearsOrders(c) || c == Cause.REINSTALL);
        }
    }

    @Test
    public void noCauseIsTreatedAsNoRule() {
        // Fails closed the useful way round: with no cause there is nothing to act
        // on, so neither store is touched.
        assertFalse(SystemReplacement.clearsSessions(null));
        assertFalse(SystemReplacement.clearsOrders(null));
    }
}
