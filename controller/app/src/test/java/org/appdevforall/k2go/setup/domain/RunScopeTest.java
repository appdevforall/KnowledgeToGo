package org.appdevforall.k2go.setup.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-434: the per-run stage latches (slice 2). Pure, so a plain JVM test. */
public class RunScopeTest {

    @Test
    public void latchIsMonotonic() {
        RunScope s = new RunScope();
        assertFalse(s.maps());
        assertFalse(s.latchMaps(false));   // no signal yet
        assertTrue(s.latchMaps(true));     // a signal latches it
        assertTrue(s.latchMaps(false));    // stays latched after the signal goes away
        assertTrue(s.maps());
    }

    @Test
    public void stagesAreIndependent() {
        RunScope s = new RunScope();
        s.latchModule(true);
        assertTrue(s.module());
        assertFalse(s.maps());
        assertFalse(s.forgejoSeed());
        assertFalse(s.rebuild());
    }

    @Test
    public void eachStageLatchesOnItsOwnSignal() {
        RunScope s = new RunScope();
        assertTrue(s.latchMaps(true));
        assertTrue(s.latchModule(true));
        assertTrue(s.latchForgejoSeed(true));
        assertTrue(s.latchRebuild(true));
        assertTrue(s.maps() && s.module() && s.forgejoSeed() && s.rebuild());
    }

    @Test
    public void readerDoesNotLatch() {
        RunScope s = new RunScope();
        assertFalse(s.rebuild());   // reading never sets it
        assertFalse(s.rebuild());
        assertFalse(s.latchRebuild(false));
    }
}
