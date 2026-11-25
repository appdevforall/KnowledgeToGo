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

    @Test public void failedRegistersWaitLongerUpToAMaximum() {
        Announcement a = new Announcement();
        assertEquals(1000, a.nextPollMs(1000, 3000));          // no class on the network yet
        long[] expected = {2000, 4000, 8000, 16000, 30000, 30000};
        for (long e : expected) {
            assertEquals(Announcement.Action.REGISTER, a.want("4F7K"));
            a.failed();
            assertEquals(e, a.nextPollMs(1000, 3000));
        }
        assertEquals(Announcement.Action.REGISTER, a.want("4F7K"));
        assertEquals(Announcement.Action.NONE, a.done(true));
        assertEquals(3000, a.nextPollMs(1000, 3000));          // on the network: the steady pace
        assertEquals(Announcement.Action.UNREGISTER, a.want(null));
        assertEquals(Announcement.Action.NONE, a.done(false));
        assertEquals(1000, a.nextPollMs(1000, 3000));          // a success clears the failures
    }

    @Test public void anotherClassStartsWithNoWait() {
        Announcement a = new Announcement();
        assertEquals(Announcement.Action.REGISTER, a.want("4F7K"));
        a.failed();
        a.want("4F7K");
        a.failed();
        assertEquals(4000, a.nextPollMs(1000, 3000));
        a.want(null);                                           // the class ended
        assertEquals(1000, a.nextPollMs(1000, 3000));
        assertEquals(Announcement.Action.REGISTER, a.want("9XQ2"));   // a new class
    }
}
