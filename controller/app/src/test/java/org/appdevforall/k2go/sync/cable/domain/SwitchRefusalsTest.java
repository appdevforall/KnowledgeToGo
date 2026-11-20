package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SwitchRefusalsTest {

    private static void fail(SwitchRefusals r, String device, int times) {
        for (int i = 0; i < times; i++) r.failed(device);
    }

    @Test
    public void threeFailuresInARowMeanNoAccessoryMode() {
        SwitchRefusals r = new SwitchRefusals();
        fail(r, "a", SwitchRefusals.TRIES - 1);
        assertFalse(r.unsupported("a"));
        r.failed("a");
        assertTrue(r.unsupported("a"));
        assertFalse(r.unsupported("b"));
    }

    @Test
    public void aSuccessOrAnotherDeviceBreaksTheRow() {
        SwitchRefusals r = new SwitchRefusals();
        fail(r, "a", SwitchRefusals.TRIES - 1);
        r.succeeded();
        fail(r, "a", SwitchRefusals.TRIES - 1);
        assertFalse(r.unsupported("a"));

        r.failed("b");
        fail(r, "a", SwitchRefusals.TRIES - 1);
        assertFalse(r.unsupported("a"));
    }

    @Test
    public void onlyTheDeviceThatLeftGetsNewTries() {
        SwitchRefusals r = new SwitchRefusals();
        fail(r, "a", SwitchRefusals.TRIES);
        r.forget("drive");
        assertTrue(r.unsupported("a"));
        r.forget("a");
        assertFalse(r.unsupported("a"));
    }

    @Test
    public void clearGivesTheDeviceNewTries() {
        SwitchRefusals r = new SwitchRefusals();
        fail(r, "a", SwitchRefusals.TRIES);
        r.clear();
        assertFalse(r.unsupported("a"));
        fail(r, "a", SwitchRefusals.TRIES - 1);
        assertFalse(r.unsupported("a"));
    }
}
