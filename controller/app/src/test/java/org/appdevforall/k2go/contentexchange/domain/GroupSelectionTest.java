package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-logic tests for the group select-all tri-state. */
public class GroupSelectionTest {

    @Test
    public void noneWhenNothingSelected() {
        assertEquals(GroupSelection.NONE, GroupSelection.state(5, 0));
    }

    @Test
    public void someWhenPartiallySelected() {
        assertEquals(GroupSelection.SOME, GroupSelection.state(5, 2));
    }

    @Test
    public void allWhenEverySelectableIsSelected() {
        assertEquals(GroupSelection.ALL, GroupSelection.state(5, 5));
    }

    @Test
    public void noneWhenGroupHasNoSelectableMembers() {
        assertEquals(GroupSelection.NONE, GroupSelection.state(0, 0));
    }

    @Test
    public void selectedCountOverTheMemberCountStillCountsAsAll() {
        // Defensive: a selected count that exceeds the member count is still a full group.
        assertEquals(GroupSelection.ALL, GroupSelection.state(3, 4));
    }

    @Test
    public void tapSelectsAllUnlessAlreadyFull() {
        assertTrue(GroupSelection.shouldSelectAll(GroupSelection.NONE));
        assertTrue(GroupSelection.shouldSelectAll(GroupSelection.SOME));
        assertFalse(GroupSelection.shouldSelectAll(GroupSelection.ALL));
    }
}
