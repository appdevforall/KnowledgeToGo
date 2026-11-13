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
        assertEquals(-1L, m.usize);
    }

    @Test
    public void readsUsizeAndMatchesAnyTierForTheAbi() {
        RootfsManifest m = RootfsManifest.parse(GOOD + " usize=5000000000");
        assertNotNull(m);
        assertEquals(5000000000L, m.usize);
        assertTrue(m.isForAbi("arm64-v8a"));
        assertFalse(m.isForAbi("armeabi-v7a"));
        assertNull(RootfsManifest.parse(GOOD + " usize=12x"));
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

    private static final RootfsManifest.Target BASIC_ARM64 = new RootfsManifest.Target("basic", "arm64-v8a");

    @Test
    public void judgeNeedsIntegrityIdentityAndTheRequestedTarget() {
        assertEquals(RootfsManifest.Verdict.TRUSTED,
                RootfsManifest.judge(true, GOOD, NAME, 1589997791L, SHA, BASIC_ARM64));
        assertEquals("authentic manifest, but nobody hashed the bytes",
                RootfsManifest.Verdict.BYTES_NOT_VERIFIED,
                RootfsManifest.judge(false, GOOD, NAME, 1589997791L, SHA, BASIC_ARM64));
        assertEquals(RootfsManifest.Verdict.NOT_AUTHENTIC,
                RootfsManifest.judge(true, null, NAME, 1589997791L, SHA, BASIC_ARM64));
        assertEquals(RootfsManifest.Verdict.NO_MANIFEST,
                RootfsManifest.judge(true, "k2go rootfs k2go_2026.316_basic_7dbe02c_arm64-v8a",
                        NAME, 1589997791L, SHA, BASIC_ARM64));
        assertEquals("an official manifest for another artifact than the meta4 names",
                RootfsManifest.Verdict.MISMATCH,
                RootfsManifest.judge(true, GOOD, NAME.replace("basic", "full"), 1589997791L, SHA, BASIC_ARM64));
        assertEquals("an official arm64 tarball offered to a device that asked for armeabi-v7a",
                RootfsManifest.Verdict.WRONG_TARGET,
                RootfsManifest.judge(true, GOOD, NAME, 1589997791L, SHA,
                        new RootfsManifest.Target("basic", "armeabi-v7a")));
        assertEquals("an official basic tarball offered for a full install",
                RootfsManifest.Verdict.WRONG_TARGET,
                RootfsManifest.judge(true, GOOD, NAME, 1589997791L, SHA,
                        new RootfsManifest.Target("full", "arm64-v8a")));
        assertEquals("no target means not trusted",
                RootfsManifest.Verdict.WRONG_TARGET,
                RootfsManifest.judge(true, GOOD, NAME, 1589997791L, SHA, null));
    }

    @Test
    public void isForMatchesOnlyTheBakeArtifactName() {
        RootfsManifest m = RootfsManifest.parse(GOOD);
        assertTrue(m.isFor(BASIC_ARM64));
        assertFalse(m.isFor(new RootfsManifest.Target("basic", "arm64-v8")));
        assertFalse(m.isFor(new RootfsManifest.Target("bas", "arm64-v8a")));
        assertFalse(m.isFor(new RootfsManifest.Target(".*", ".*")));
        assertFalse(RootfsManifest.parse(GOOD.replace("file=" + NAME, "file=other_basic_arm64-v8a.tar.gz"))
                .isFor(BASIC_ARM64));
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
