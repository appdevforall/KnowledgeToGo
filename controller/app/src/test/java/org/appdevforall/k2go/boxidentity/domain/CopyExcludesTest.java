package org.appdevforall.k2go.boxidentity.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class CopyExcludesTest {

    private static final String TEXT = "# comment\n\nlibrary/kolibri/logs/*.txt\r\n  /tmp/*  \n";

    @Test
    public void dropsCommentsAndBlankLines() {
        assertEquals(Arrays.asList("library/kolibri/logs/*.txt", "tmp/*"), CopyExcludes.patterns(TEXT));
    }

    @Test
    public void anchorsAtTheRsyncModuleRoot() {
        assertEquals("/library/kolibri/logs/*.txt\n/tmp/*\n/sdcard/\n", CopyExcludes.forRsync(TEXT));
    }

    @Test
    public void anchorsAtTheTarMemberPrefix() {
        assertEquals("installed-rootfs/iiab/library/kolibri/logs/*.txt\ninstalled-rootfs/iiab/tmp/*\n"
                        + "installed-rootfs/iiab/sdcard/*\n",
                CopyExcludes.forTar(TEXT, "installed-rootfs/iiab/"));
    }

    @Test
    public void mountPointIsNeverOpenedNorDeleted() {
        // rsync: the whole dir is excluded (a read error stops all deletions), and protected.
        assertTrue(CopyExcludes.forRsync("").contains("/sdcard/\n"));
        assertEquals(Arrays.asList("P /sdcard/"), CopyExcludes.rsyncProtectRules());
    }

    @Test
    public void shippedListIsSafe() throws IOException {
        byte[] raw = Files.readAllBytes(new File("src/main/assets/" + CopyExcludes.ASSET).toPath());
        for (byte b : raw) {
            assertTrue("ASCII only", b >= 0);
        }
        List<String> patterns = CopyExcludes.patterns(new String(raw, StandardCharsets.US_ASCII));
        assertTrue(patterns.contains("library/kolibri/logs/*.txt"));
        assertTrue(patterns.contains("library/dashboard/jobs.db"));
        for (String p : patterns) {
            // "**" behaves differently in rsync and tar; a bare dir name would drop a whole tree.
            assertFalse(p, p.contains("**") || p.contains("..") || p.contains(" "));
            assertFalse(p, p.equals("core") || p.equals("*") || p.endsWith("/"));
        }
    }
}
