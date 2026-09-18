package org.appdevforall.k2go.help.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure-JVM tests for the (topic, language) -> bundled page resolver (K2GO-410). */
public class HelpEntryTest {

    @Test public void resolvesTopicToItsPage() {
        assertEquals("index.html", HelpEntry.assetPath(HelpTopic.HOME, "en"));
        assertEquals("app-install.html", HelpEntry.assetPath(HelpTopic.INSTALL, "en"));
    }

    @Test public void languageIsNotUsedYet() {
        // Single-language manual today: any tag (including null/empty) resolves to the same page.
        String en = HelpEntry.assetPath(HelpTopic.INSTALL, "en");
        assertEquals(en, HelpEntry.assetPath(HelpTopic.INSTALL, "es"));
        assertEquals(en, HelpEntry.assetPath(HelpTopic.INSTALL, ""));
        assertEquals(en, HelpEntry.assetPath(HelpTopic.INSTALL, null));
    }

    @Test public void nullTopicResolvesToHome() {
        assertEquals("index.html", HelpEntry.assetPath(null, "en"));
    }
}
