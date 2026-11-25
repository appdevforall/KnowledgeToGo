package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.school.domain.Announcement.Action;
import org.junit.Test;

/** Pure-JVM tests for the steps of the class announcement. */
public class AnnouncementTest {

    @Test public void openClassRegistersOnceAndClosedClassUnregisters() {
        Announcement a = new Announcement();
        assertEquals(Action.NONE, a.want(false));
        assertEquals(Action.REGISTER, a.want(true));
        assertEquals(Action.NONE, a.want(true));          // the register still runs
        assertEquals(Action.NONE, a.done(true));
        assertTrue(a.registered());
        assertEquals(Action.NONE, a.want(true));          // each poll says open: nothing to do
        assertEquals(Action.UNREGISTER, a.want(false));
        assertEquals(Action.NONE, a.done(false));
        assertFalse(a.registered());
    }

    @Test public void closeDuringTheRegisterUnregistersWhenTheRegisterEnds() {
        Announcement a = new Announcement();
        assertEquals(Action.REGISTER, a.want(true));
        assertEquals(Action.NONE, a.want(false));         // the screen closes while it runs
        assertEquals(Action.UNREGISTER, a.done(true));    // the service came on: take it off
        assertEquals(Action.NONE, a.done(false));
    }

    @Test public void openAgainDuringTheUnregisterRegistersAgain() {
        Announcement a = new Announcement();
        a.want(true);
        a.done(true);
        assertEquals(Action.UNREGISTER, a.want(false));
        assertEquals(Action.NONE, a.want(true));
        assertEquals(Action.REGISTER, a.done(false));
    }

    @Test public void aFailedStepIsTriedAgainOnlyAtTheNextAnswer() {
        Announcement a = new Announcement();
        assertEquals(Action.REGISTER, a.want(true));
        a.failed();
        assertFalse(a.registered());
        assertEquals(Action.REGISTER, a.want(true));
    }
}
