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
    public void phoneInUsbTetheringModeIsACandidate() {
        assertTrue(UsbCandidate.canBePhone(false, new int[]{2, 10}));      // NCM
        assertTrue(UsbCandidate.canBePhone(false, new int[]{0xE0, 10}));   // RNDIS
        assertTrue(UsbCandidate.canBePhone(false, new int[]{0xEF, 10}));   // RNDIS, misc class
    }

    @Test
    public void hubDriveKeyboardAndAudioAreNot() {
        assertFalse(UsbCandidate.canBePhone(false, new int[]{9}));
        assertFalse(UsbCandidate.canBePhone(false, new int[]{8}));
        assertFalse(UsbCandidate.canBePhone(false, new int[]{3, 3}));
        assertFalse(UsbCandidate.canBePhone(false, new int[]{1, 1, 3}));
        assertFalse(UsbCandidate.canBePhone(false, new int[]{0x0E, 0x0E}));   // webcam
    }

    @Test
    public void appleHasNoAccessoryMode() {
        assertTrue(UsbCandidate.hasNoAccessoryMode(0x05AC));
        assertFalse(UsbCandidate.hasNoAccessoryMode(0x04E8));   // Samsung
        assertFalse(UsbCandidate.hasNoAccessoryMode(0x18D1));   // Google, also the AOA ids
    }
}
