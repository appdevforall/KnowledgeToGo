package org.appdevforall.k2go.deploy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class RootfsArchiveTest {

    @Test public void recognisesABackupRootfs() {
        assertTrue(RootfsArchive.looksLikeRootfs(Arrays.asList(
                "installed-rootfs/etc/hosts", "installed-rootfs/bin/bash", "installed-rootfs/usr/")));
    }

    @Test public void recognisesAPlainRootfs() {
        assertTrue(RootfsArchive.looksLikeRootfs(Arrays.asList("./etc/os-release", "./usr/bin/dpkg")));
    }

    @Test public void rejectsNonRootfs() {
        assertFalse(RootfsArchive.looksLikeRootfs(Arrays.asList("wikipedia_en_all.zim")));
        assertFalse(RootfsArchive.looksLikeRootfs(Collections.<String>emptyList()));
        assertFalse(RootfsArchive.looksLikeRootfs(null));
    }

    @Test public void picksAKnownBinaryReturningOriginalName() {
        assertEquals("installed-rootfs/bin/bash",
                RootfsArchive.pickBinaryEntry(Arrays.asList(
                        "installed-rootfs/etc/hosts", "installed-rootfs/bin/bash")));
        assertNull(RootfsArchive.pickBinaryEntry(Arrays.asList("etc/hosts", "var/log/x")));
    }
}
