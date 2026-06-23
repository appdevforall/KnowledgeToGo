package org.iiab.controller.update.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckTest {

    @Test
    public void baseStripsTheAbiDigit() {
        assertEquals(50, UpdateCheck.baseOf(500));
        assertEquals(50, UpdateCheck.baseOf(501));
        assertEquals(50, UpdateCheck.baseOf(509));
    }

    @Test
    public void offersUpdateOnlyWhenServerBaseIsStrictlyNewer() {
        assertTrue(UpdateCheck.isUpdateAvailable(51, 500));   // server 51 > local base 50
        assertFalse(UpdateCheck.isUpdateAvailable(50, 500));  // equal
        assertFalse(UpdateCheck.isUpdateAvailable(49, 500));  // older
        assertFalse(UpdateCheck.isUpdateAvailable(50, 509));  // same base, different ABI digit
    }
}
