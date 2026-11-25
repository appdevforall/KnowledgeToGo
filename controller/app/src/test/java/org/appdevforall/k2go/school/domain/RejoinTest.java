package org.appdevforall.k2go.school.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.school.domain.Rejoin.Action;
import org.junit.Test;

/** Pure-JVM tests for the rule that finds a class again after a drop. */
public class RejoinTest {

    private static FoundClass at(String code, String address) {
        return FoundClass.of("K2Go class " + code, code, address, 8085, 8085);
    }

    @Test public void theClassAtItsAddressNeedsNothing() {
        Rejoin r = new Rejoin("4F7K", "192.168.1.167");
        for (int i = 0; i < 10; i++) assertEquals(Action.NONE, r.probed("4F7K"));
        assertEquals(Action.NONE, r.found(at("4F7K", "192.168.1.170")));   // no search: no move
    }

    @Test public void twoMissesStartTheSearchAndTheSameCodeElsewhereMoves() {
        Rejoin r = new Rejoin("4F7K", "192.168.1.167");
        assertEquals(Action.NONE, r.probed(null));       // one miss: a short Wi-Fi drop
        assertEquals(Action.SEARCH, r.probed(null));
        assertTrue(r.searching());
        assertEquals(Action.NONE, r.found(at("9XQ2", "192.168.1.170")));    // another class
        assertEquals(Action.NONE, r.found(at("4F7K", "192.168.1.167")));    // the old address
        FoundClass moved = at("4F7K", "192.168.1.170");
        assertEquals(Action.CHECK, r.found(moved));
        assertEquals(Action.NONE, r.confirmed(moved, null));         // an old address: no box there
        assertEquals(Action.NONE, r.confirmed(moved, "9XQ2"));       // another class there now
        assertTrue(r.searching());
        assertFalse(r.isClassHost("192.168.1.170"));                 // found, not confirmed: not allowed
        assertEquals(Action.MOVE, r.confirmed(moved, "4F7K"));
        assertTrue(r.isClassHost("192.168.1.170"));
        assertTrue(r.isClassHost("192.168.1.167"));
        assertFalse(r.isClassHost("192.168.1.1"));
        assertFalse(r.isClassHost(null));
        assertEquals("192.168.1.170", r.address());
        assertFalse(r.searching());
        assertEquals(Action.NONE, r.probed("4F7K"));
    }

    @Test public void theSearchStartsAgainAndStopsWhenTheClassComesBackOrEnds() {
        Rejoin r = new Rejoin("4F7K", "192.168.1.167");
        r.probed(null);
        assertEquals(Action.SEARCH, r.probed(null));
        assertEquals(Action.NONE, r.probed(null));
        assertEquals(Action.SEARCH, r.probed(null));     // again: the network may have a fresh address
        assertEquals(Action.STOP_SEARCH, r.probed("4F7K"));   // back at the same address
        assertEquals(Action.NONE, r.probed("9XQ2"));     // another class there: a miss
        assertEquals(Action.SEARCH, r.probed(null));
        Action last = Action.NONE;
        for (int i = 3; i <= Rejoin.GIVE_UP_MISSES; i++) last = r.probed(null);
        assertEquals(Action.STOP_SEARCH, last);          // the class ended: no search for ever
        assertTrue(r.gaveUp());                          // the screen probes less often
        assertEquals(Action.NONE, r.probed(null));
        assertFalse(r.searching());
        assertEquals(Action.NONE, r.probed("4F7K"));     // back at the address after all
        assertFalse(r.gaveUp());
    }

    @Test public void aBoxThatSaysNoClassEndsTheSearchForGood() {
        Rejoin r = new Rejoin("4F7K", "192.168.1.167");
        assertEquals(Action.NONE, r.probed(null, false));       // no answer: a miss
        assertEquals(Action.SEARCH, r.probed(null, false));
        assertEquals(Action.NONE, r.probed("9XQ2", true));     // another class there: a miss
        assertFalse(r.ended());
        assertEquals(Action.END, r.probed(null, true));         // the box says: no class open
        assertTrue(r.ended());
        assertFalse(r.searching());
        assertEquals(Action.NONE, r.probed("4F7K", true));     // nothing after the end
        FoundClass moved = at("4F7K", "192.168.1.170");
        assertEquals(Action.NONE, r.found(moved));
        assertEquals(Action.NONE, r.confirmed(moved, "4F7K"));
        assertEquals("192.168.1.167", r.address());
    }
}
