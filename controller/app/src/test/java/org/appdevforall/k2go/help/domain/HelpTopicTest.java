package org.appdevforall.k2go.help.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** Pure-JVM tests for the help content axis (K2GO-410). */
public class HelpTopicTest {

    @Test public void fileNamesMapToBundledPages() {
        assertEquals("index.html", HelpTopic.HOME.fileName());
        assertEquals("app-install.html", HelpTopic.INSTALL.fileName());
    }

    @Test public void fromNameParsesKnownConstant() {
        assertSame(HelpTopic.INSTALL, HelpTopic.fromName("INSTALL", HelpTopic.HOME));
    }

    @Test public void fromNameFallsBackForNullOrUnknown() {
        assertSame(HelpTopic.HOME, HelpTopic.fromName(null, HelpTopic.HOME));
        assertSame(HelpTopic.HOME, HelpTopic.fromName("does-not-exist", HelpTopic.HOME));
        assertSame(HelpTopic.INSTALL, HelpTopic.fromName("nope", HelpTopic.INSTALL));
    }
}
