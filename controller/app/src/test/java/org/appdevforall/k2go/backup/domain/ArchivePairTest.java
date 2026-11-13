package org.appdevforall.k2go.backup.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ArchivePairTest {

    private static final String TAR = "k2go_2026.317_1_aarch64.tar.gz";

    @Test
    public void namesBothWays() {
        assertEquals("k2go_2026.317_1_aarch64.tar.json", ArchivePair.json(TAR));
        assertEquals(TAR + ".minisig", ArchivePair.signature(TAR));
        assertEquals(TAR, ArchivePair.tarball(ArchivePair.json(TAR)));
        assertEquals(TAR, ArchivePair.tarball(ArchivePair.signature(TAR)));
    }

    @Test
    public void kinds() {
        assertEquals(ArchivePair.Kind.TARBALL, ArchivePair.kind(TAR));
        assertEquals(ArchivePair.Kind.JSON, ArchivePair.kind("a.tar.json"));
        assertEquals(ArchivePair.Kind.SIGNATURE, ArchivePair.kind("a.tar.gz.minisig"));
        assertEquals(ArchivePair.Kind.OTHER, ArchivePair.kind("backup.zip"));
        assertEquals(ArchivePair.Kind.OTHER, ArchivePair.kind(".tar.gz"));
        assertEquals(ArchivePair.Kind.OTHER, ArchivePair.kind(null));
    }

    @Test
    public void otherNamesHaveNoPair() {
        assertNull(ArchivePair.json("backup.zip"));
        assertNull(ArchivePair.signature("a.tar.json"));
        assertNull(ArchivePair.tarball("backup.json"));
        assertNull(ArchivePair.tarball(TAR));
    }
}
