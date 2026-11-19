package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbCandidateTest {

    @Test
    public void phoneInMtpOrAdbModeIsACandidate() {
        assertTrue(UsbCandidate.canBePhone(false, new int[]{6}));
        assertTrue(UsbCandidate.canBePhone(false, new int[]{255, 255}));
    }

    @Test
    public void accessoryModeOrChargeOnlyIsACandidate() {
        assertTrue(UsbCandidate.canBePhone(true, new int[]{255}));
        assertTrue(UsbCandidate.canBePhone(false, new int[0]));
    }

    @Test
    public void hubDriveKeyboardAndAudioAreNot() {
        assertFalse(UsbCandidate.canBePhone(false, new int[]{9}));
        assertFalse(UsbCandidate.canBePhone(false, new int[]{8}));
        assertFalse(UsbCandidate.canBePhone(false, new int[]{3, 3}));
        assertFalse(UsbCandidate.canBePhone(false, new int[]{1, 1, 3}));
    }
}
