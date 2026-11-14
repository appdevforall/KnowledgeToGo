package org.appdevforall.k2go.deviceinfo.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PlatformInfoTest {

    private static PlatformInfo lib(boolean tree, String abi) {
        return new PlatformInfo("16", 36, "arm64-v8a", true, "arm64-v8a", tree, abi);
    }

    @Test
    public void noTreeIsNotInstalledEvenWithAnAbi() {
        PlatformInfo p = lib(false, "armeabi-v7a");
        assertEquals(PlatformInfo.LibraryState.NOT_INSTALLED, p.libraryState);
        assertNull(p.libraryAbi);
    }

    @Test
    public void treeWithAbiIsKnown() {
        PlatformInfo p = lib(true, "armeabi-v7a");
        assertEquals(PlatformInfo.LibraryState.KNOWN, p.libraryState);
        assertEquals("armeabi-v7a", p.libraryAbi);
    }

    @Test
    public void treeWithoutAbiIsUnknown() {
        assertEquals(PlatformInfo.LibraryState.UNKNOWN, lib(true, null).libraryState);
    }
}
