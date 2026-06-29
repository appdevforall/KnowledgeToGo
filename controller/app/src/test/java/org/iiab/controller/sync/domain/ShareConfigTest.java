package org.iiab.controller.sync.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for {@link ShareConfig} defaults (S14 step 2). Pure JVM. */
public class ShareConfigTest {

    @Test
    public void defaultsMatchTheLegacyHardcodedValues() {
        ShareConfig c = ShareConfig.defaults();
        assertEquals(8730, c.rsyncPort);
        assertEquals(8080, c.apkPort);
        assertEquals("iiab_peer", c.user);
        assertEquals("iiab_sync", c.moduleName);
    }

    @Test
    public void honoursExplicitValues() {
        ShareConfig c = new ShareConfig(9000, 9090, "peer", "projects");
        assertEquals(9000, c.rsyncPort);
        assertEquals(9090, c.apkPort);
        assertEquals("peer", c.user);
        assertEquals("projects", c.moduleName);
    }
}
