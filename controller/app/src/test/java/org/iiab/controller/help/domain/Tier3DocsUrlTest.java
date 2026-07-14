package org.iiab.controller.help.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure-JVM tests for tier-3 docs URL building. */
public class Tier3DocsUrlTest {

    @Test public void articleUrlUsesTooltipId() {
        assertEquals("http://localhost:8085/k2go-docs/sync_start_transfer",
                Tier3DocsUrl.forTooltip("sync_start_transfer"));
    }

    @Test public void blankOrNullFallsBackToHome() {
        assertEquals(Tier3DocsUrl.BASE, Tier3DocsUrl.forTooltip("   "));
        assertEquals(Tier3DocsUrl.BASE, Tier3DocsUrl.forTooltip(null));
    }

    @Test public void trimsWhitespace() {
        assertEquals("http://localhost:8085/k2go-docs/foo", Tier3DocsUrl.forTooltip(" foo "));
    }
}
