package org.appdevforall.k2go.rootfs.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The tier id is the token in the published filenames (latest_<tier>_<abi>.meta4, k2go_..._<tier>_...). */
public class RootfsTierTest {

    @Test
    public void idsMatchThePublishedFilenameTokens() {
        assertEquals("basic", RootfsTier.BASIC.id());
        assertEquals("standard", RootfsTier.STANDARD.id());
        assertEquals("full", RootfsTier.FULL.id());
    }
}
