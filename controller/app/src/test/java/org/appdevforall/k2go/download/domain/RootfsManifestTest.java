package org.appdevforall.k2go.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-554 ADR Amendment 1 (A1/A2): strict parse of the manifest in the minisign trusted comment. */
public class RootfsManifestTest {

    private static final String SHA = "818fbeb8bba70e970170241adad917ce3cad2a9e0e1821f64bac815c3a80f8cf";
    private static final String M4 = "ecadfaf3612302510000000000000000000000000000000000000000000000ab";
    private static final String NAME = "k2go_2026.316_basic_7dbe02c_arm64-v8a.tar.gz";
    private static final String GOOD =
            "k2go-rootfs v1 file=" + NAME + " size=1589997791 sha256=" + SHA + " meta4=" + M4;

    @Test
    public void parsesTheBakeManifest() {
        RootfsManifest m = RootfsManifest.parse(GOOD);
        assertNotNull(m);
        assertEquals(NAME, m.file);
        assertEquals(1589997791L, m.size);
        assertEquals(SHA, m.sha256);
        assertEquals(M4, m.meta4);
    }

    @Test
    public void meta4IsOptional() {
        RootfsManifest m = RootfsManifest.parse("k2go-rootfs v1 file=" + NAME + " size=1 sha256=" + SHA);
        assertNotNull(m);
        assertNull(m.meta4);
    }

    @Test
    public void legacyAndForeignCommentsAreNotManifests() {
        assertNull(RootfsManifest.parse(null));
        assertNull(RootfsManifest.parse("k2go rootfs k2go_2026.316_full_7dbe02c_arm64-v8a"));
        assertNull(RootfsManifest.parse("timestamp:1700000000\tfile:x.tar.gz"));
        assertNull(RootfsManifest.parse(GOOD.replace("k2go-rootfs v1 ", "k2go-rootfs v2 ")));
    }

    @Test
    public void rejectsMissingRequiredFields() {
        assertNull(RootfsManifest.parse("k2go-rootfs v1 size=1 sha256=" + SHA));
        assertNull(RootfsManifest.parse("k2go-rootfs v1 file=" + NAME + " sha256=" + SHA));
        assertNull(RootfsManifest.parse("k2go-rootfs v1 file=" + NAME + " size=1"));
    }

    @Test
    public void rejectsMalformedValues() {
        assertNull(RootfsManifest.parse(GOOD.replace("size=1589997791", "size=-1")));
        assertNull(RootfsManifest.parse(GOOD.replace("size=1589997791", "size=1e9")));
        assertNull(RootfsManifest.parse(GOOD.replace(SHA, SHA.toUpperCase())));
        assertNull(RootfsManifest.parse(GOOD.replace(SHA, SHA.substring(1))));
        assertNull(RootfsManifest.parse(GOOD.replace(M4, "zz")));
        assertNull(RootfsManifest.parse(GOOD.replace("file=" + NAME, "file=")));
    }

    @Test
    public void rejectsUnknownOrRepeatedKeys() {
        assertNull(RootfsManifest.parse(GOOD + " torrent=" + SHA));
        assertNull(RootfsManifest.parse(GOOD + " size=1"));
    }

    @Test
    public void judgeNeedsBothIntegrityAndIdentity() {
        assertEquals(RootfsManifest.Verdict.TRUSTED,
                RootfsManifest.judge(true, GOOD, NAME, 1589997791L, SHA));
        assertEquals("authentic manifest, but nobody hashed the bytes",
                RootfsManifest.Verdict.BYTES_NOT_VERIFIED,
                RootfsManifest.judge(false, GOOD, NAME, 1589997791L, SHA));
        assertEquals(RootfsManifest.Verdict.NOT_AUTHENTIC,
                RootfsManifest.judge(true, null, NAME, 1589997791L, SHA));
        assertEquals(RootfsManifest.Verdict.NO_MANIFEST,
                RootfsManifest.judge(true, "k2go rootfs k2go_2026.316_basic_7dbe02c_arm64-v8a", NAME, 1589997791L, SHA));
        assertEquals("an official manifest for another artifact",
                RootfsManifest.Verdict.MISMATCH,
                RootfsManifest.judge(true, GOOD, NAME.replace("basic", "full"), 1589997791L, SHA));
    }

    @Test
    public void vouchesOnlyForTheNamedArtifact() {
        RootfsManifest m = RootfsManifest.parse(GOOD);
        assertTrue(m.vouchesFor(NAME, 1589997791L, SHA));
        assertTrue("meta4 hex may be upper case", m.vouchesFor(NAME, 1589997791L, SHA.toUpperCase()));
        assertFalse(m.vouchesFor("k2go_2026.316_full_7dbe02c_arm64-v8a.tar.gz", 1589997791L, SHA));
        assertFalse(m.vouchesFor(NAME, 1589997790L, SHA));
        assertFalse(m.vouchesFor(NAME, 1589997791L, SHA.replace('8', '9')));
        assertFalse(m.vouchesFor(NAME, 1589997791L, null));
    }
}
