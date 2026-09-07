package org.appdevforall.k2go.networkpolicy.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the "warn on becoming metered" edge rule. */
public class NetworkTransitionTest {

    @Test
    public void unmeteredToMetered_warns() {
        assertTrue(NetworkTransition.shouldWarn(NetworkClass.UNMETERED, NetworkClass.METERED));
    }

    @Test
    public void noneToMetered_warns() {
        assertTrue(NetworkTransition.shouldWarn(NetworkClass.NONE, NetworkClass.METERED));
    }

    @Test
    public void meteredToMetered_doesNotWarn() {
        assertFalse(NetworkTransition.shouldWarn(NetworkClass.METERED, NetworkClass.METERED));
    }

    @Test
    public void meteredToUnmetered_doesNotWarn() {
        assertFalse(NetworkTransition.shouldWarn(NetworkClass.METERED, NetworkClass.UNMETERED));
    }
}
