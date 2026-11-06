/*
 * ============================================================================
 * Name        : BoxIdentityLineTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-553. Unit tests for BoxIdentityLine.
 * ============================================================================
 */
package org.appdevforall.k2go.boxidentity.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoxIdentityLineTest {

    @Test
    public void beginLineMatches() {
        assertTrue(BoxIdentityLine.isBegin("[k2go-box-identity] begin"));
        assertTrue(BoxIdentityLine.isBegin("  [k2go-box-identity] begin\r"));
        assertTrue(BoxIdentityLine.isBegin("[Debian] [k2go-box-identity] begin"));
    }

    @Test
    public void otherLinesDoNotMatch() {
        assertFalse(BoxIdentityLine.isBegin(null));
        assertFalse(BoxIdentityLine.isBegin(""));
        assertFalse(BoxIdentityLine.isBegin("[k2go-box-identity] end"));
        assertFalse(BoxIdentityLine.isBegin("[k2go-box-identity] kolibri: provisiondevice (10s)"));
        assertFalse(BoxIdentityLine.isBegin("[k2go-box-identity] begin now"));
        assertFalse(BoxIdentityLine.isBegin("x[k2go-box-identity] begin"));
        assertFalse(BoxIdentityLine.isBegin("[pdsm:kolibri] begin"));
    }
}
