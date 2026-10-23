package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The phone-disk preflight rule (the disparate-storage crux). */
public class ImportPreflightTest {

    @Test
    public void fitsWhenSelectionIsWithinFreeSpace() {
        assertTrue(ImportPreflight.fits(100L, 200L));
        assertTrue(ImportPreflight.fits(200L, 200L));   // exact fit
    }

    @Test
    public void doesNotFitWhenLargerThanFreeSpace() {
        assertFalse(ImportPreflight.fits(201L, 200L));
    }

    @Test
    public void unknownFreeSpaceDoesNotFit() {
        assertFalse(ImportPreflight.fits(1L, null));
    }

    @Test
    public void emptySelectionAlwaysFits() {
        assertTrue(ImportPreflight.fits(0L, null));
        assertTrue(ImportPreflight.fits(0L, 10L));
    }
}
