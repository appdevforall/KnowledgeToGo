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
        assertEquals(Action.MOVE, r.found(at("4F7K", "192.168.1.170")));
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
        assertEquals(Action.NONE, r.probed(null));
        assertFalse(r.searching());
    }
}
