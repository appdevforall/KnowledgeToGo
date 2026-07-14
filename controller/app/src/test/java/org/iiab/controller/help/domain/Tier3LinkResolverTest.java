package org.iiab.controller.help.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure-JVM tests for tier-3 help link state resolution. */
public class Tier3LinkResolverTest {

    @Test public void noRootfsIsDisabled() {
        assertEquals(Tier3LinkState.NO_ROOTFS, Tier3LinkResolver.resolve(false, false));
        assertEquals(Tier3LinkState.NO_ROOTFS, Tier3LinkResolver.resolve(false, true));
    }

    @Test public void rootfsButServerOffIsOff() {
        assertEquals(Tier3LinkState.SERVER_OFF, Tier3LinkResolver.resolve(true, false));
    }

    @Test public void rootfsAndServerAliveIsLive() {
        assertEquals(Tier3LinkState.LIVE, Tier3LinkResolver.resolve(true, true));
    }
}
