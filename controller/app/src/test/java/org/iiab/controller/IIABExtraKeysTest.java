package org.iiab.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.termux.shared.termux.extrakeys.ExtraKeysInfo;

import org.junit.Test;

/**
 * Unit tests for {@link IIABExtraKeys} (finding K5). These validate that the layout
 * constants parse into the intended grid shape, catching a malformed layout edit before
 * it ships. Runs on the plain JVM via {@code testOptions.unitTests.returnDefaultValues}
 * + the {@code org.json} test dependency added in Phase 0.
 *
 * <p>Assertions are limited to grid structure (rows × keys), which is the part most likely
 * to break on a bad edit and is independent of Android framework stubs.
 */
public class IIABExtraKeysTest {

    @Test
    public void defaultLayout_parsesIntoTwoRowsOfSevenKeys() throws Exception {
        ExtraKeysInfo info = IIABExtraKeys.buildInfo(IIABExtraKeys.DEFAULT_LAYOUT);
        assertNotNull(info);
        assertEquals("default layout should have 2 rows", 2, info.getMatrix().length);
        assertEquals("row 1 should have 7 keys", 7, info.getMatrix()[0].length);
        assertEquals("row 2 should have 7 keys", 7, info.getMatrix()[1].length);
    }

    @Test
    public void fallbackLayout_parsesIntoSingleRowOfSevenKeys() throws Exception {
        ExtraKeysInfo info = IIABExtraKeys.buildInfo(IIABExtraKeys.FALLBACK_LAYOUT);
        assertNotNull(info);
        assertEquals("fallback layout should have 1 row", 1, info.getMatrix().length);
        assertEquals("fallback row should have 7 keys", 7, info.getMatrix()[0].length);
    }
}
