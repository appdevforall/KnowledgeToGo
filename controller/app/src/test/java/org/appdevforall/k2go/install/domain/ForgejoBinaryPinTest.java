/*
 * ============================================================================
 * Name        : ForgejoBinaryPinTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-593. Unit tests for the forgejo_version pin in local_vars.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ForgejoBinaryPinTest {

    @Test
    public void pinWritesOneMarkedLineAfterItRemovesAnOldOne() {
        assertEquals("sed -i '/# k2go-forgejo-bin$/d' /etc/iiab/local_vars.yml"
                        + " && echo 'forgejo_version: \"17.0.2\"  # k2go-forgejo-bin' >> /etc/iiab/local_vars.yml",
                ForgejoBinaryPin.pinCommand("17.0.2"));
    }

    @Test
    public void unpinRemovesOnlyTheMarkedLine() {
        assertEquals("sed -i '/# k2go-forgejo-bin$/d' /etc/iiab/local_vars.yml", ForgejoBinaryPin.UNPIN);
    }

    /** The version comes from the box and goes into a root shell command: only X.Y.Z is accepted. */
    @Test
    public void onlyAPlainVersionIsPinned() {
        assertTrue(ForgejoBinaryPin.isVersion("17.0.2"));
        for (String bad : new String[]{null, "", "latest", "v17.0.2", "17.0", "17.0.2-rc1",
                "17.0.2'; rm -rf /", "17.0.2\n", "1.2.3 4"}) {
            assertFalse(String.valueOf(bad), ForgejoBinaryPin.isVersion(bad));
            assertNull(String.valueOf(bad), ForgejoBinaryPin.pinCommand(bad));
        }
    }

    @Test
    public void ownerPinIsAnUnmarkedVersionLine() {
        assertEquals("17.0.1", ForgejoBinaryPin.ownerPin(java.util.Arrays.asList(
                "forgejo_install: True", "forgejo_version: 17.0.1")));
        assertEquals("17.0.1", ForgejoBinaryPin.ownerPin(java.util.Arrays.asList(
                "forgejo_version: \"17.0.1\"   # keep this release")));
        assertEquals("17.0.1", ForgejoBinaryPin.ownerPin(java.util.Arrays.asList(
                "forgejo_version: '16.0.0'", "forgejo_version: 17.0.1")));   // the last one wins
    }

    @Test
    public void ourOwnPinLatestAndCommentsAreNotAnOwnerPin() {
        assertNull(ForgejoBinaryPin.ownerPin(java.util.Arrays.asList(
                "forgejo_version: \"17.0.2\"  # k2go-forgejo-bin")));
        assertNull(ForgejoBinaryPin.ownerPin(java.util.Arrays.asList("forgejo_version: latest")));
        assertNull(ForgejoBinaryPin.ownerPin(java.util.Arrays.asList("#forgejo_version: 17.0.1")));
        assertNull(ForgejoBinaryPin.ownerPin(java.util.Collections.<String>emptyList()));
        assertNull(ForgejoBinaryPin.ownerPin(null));
    }

    @Test
    public void forgejoGoesFirstAndTheOthersKeepTheirOrder() {
        assertArrayEquals(new String[]{"forgejo", "kiwix", "kolibri"},
                ForgejoBinaryPin.downloadFirst(new String[]{"kiwix", "forgejo", "kolibri"}));
        assertArrayEquals(new String[]{"kiwix", "kolibri"},
                ForgejoBinaryPin.downloadFirst(new String[]{"kiwix", "kolibri"}));
    }

    @Test
    public void jobTypeIsTheDashNodeType() {
        assertEquals("forgejo-bin", ForgejoBinaryPin.JOB_TYPE);
        assertEquals("forgejo", ForgejoBinaryPin.MODULE);
    }
}
