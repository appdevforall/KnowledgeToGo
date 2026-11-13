package org.appdevforall.k2go.arch.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LibraryArchTest {

    @Test
    public void sameWidthRuns() {
        assertTrue(LibraryArch.canRun(64, 64, false));
        assertTrue(LibraryArch.canRun(32, 32, false));
    }

    @Test
    public void a32BitLibraryRunsOnA64BitAppOnlyWhenThePhoneRuns32Bit() {
        assertTrue(LibraryArch.canRun(32, 64, true));
        assertFalse(LibraryArch.canRun(32, 64, false));   // 64-bit-only phone
    }

    @Test
    public void a64BitLibraryNeverRunsOnA32BitApp() {
        assertFalse(LibraryArch.canRun(64, 32, true));
        assertFalse(LibraryArch.canRun(64, 32, false));
    }

    @Test
    public void anUnknownWidthNeverRuns() {
        assertFalse(LibraryArch.canRun(0, 64, true));
        assertFalse(LibraryArch.canRun(64, 0, true));
        assertFalse(LibraryArch.canRun("x86_64", LibraryArch.ARM64, true));
        assertFalse(LibraryArch.canRun(null, LibraryArch.ARM64, true));
    }

    @Test
    public void abiIdsMapToWidthsAndDebianArchs() {
        assertEquals(64, LibraryArch.bitsOf(LibraryArch.ARM64));
        assertEquals(32, LibraryArch.bitsOf(LibraryArch.ARM32));
        assertEquals(0, LibraryArch.bitsOf("arm64"));
        assertEquals(LibraryArch.ARM64, LibraryArch.abiOf(64));
        assertEquals(LibraryArch.ARM32, LibraryArch.abiOf(32));
        assertNull(LibraryArch.abiOf(0));
        assertEquals("arm64", LibraryArch.debArchOf(LibraryArch.ARM64));
        assertEquals("armhf", LibraryArch.debArchOf(LibraryArch.ARM32));
        assertNull(LibraryArch.debArchOf(null));
        assertTrue(LibraryArch.canRun(LibraryArch.ARM32, LibraryArch.ARM64, true));
    }
}
