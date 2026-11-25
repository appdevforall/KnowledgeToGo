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
        assertEquals(Action.NONE, a.want(null));
        assertEquals(Action.REGISTER, a.want("4F7K"));
        assertEquals("4F7K", a.toRegister());
        assertEquals(Action.NONE, a.want("4F7K"));        // the register still runs
        assertEquals(Action.NONE, a.done(true));
        assertTrue(a.registered());
        assertEquals(Action.NONE, a.want("4F7K"));        // each poll says the same class
        assertEquals(Action.UNREGISTER, a.want(null));
        assertEquals(Action.NONE, a.done(false));
        assertFalse(a.registered());
    }

    @Test public void aNewCodeTakesTheOldClassOffFirst() {
        Announcement a = new Announcement();
        a.want("4F7K");
        a.done(true);
        assertEquals(Action.UNREGISTER, a.want("9XQ2"));
        assertEquals(Action.REGISTER, a.done(false));
        assertEquals("9XQ2", a.toRegister());
        assertEquals(Action.NONE, a.done(true));
    }

    @Test public void closeDuringTheRegisterUnregistersWhenTheRegisterEnds() {
        Announcement a = new Announcement();
        assertEquals(Action.REGISTER, a.want("4F7K"));
        assertEquals(Action.NONE, a.want(null));          // the screen closes while it runs
        assertEquals(Action.UNREGISTER, a.done(true));    // the service came on: take it off
        assertEquals(Action.NONE, a.done(false));
    }

    @Test public void openAgainDuringTheUnregisterRegistersAgain() {
        Announcement a = new Announcement();
        a.want("4F7K");
        a.done(true);
        assertEquals(Action.UNREGISTER, a.want(null));
        assertEquals(Action.NONE, a.want("4F7K"));
        assertEquals(Action.REGISTER, a.done(false));
    }

    @Test public void aFailedStepIsTriedAgainOnlyAtTheNextAnswer() {
        Announcement a = new Announcement();
        assertEquals(Action.REGISTER, a.want("4F7K"));
        a.failed();
        assertFalse(a.registered());
        assertEquals(Action.REGISTER, a.want("4F7K"));
    }
}
