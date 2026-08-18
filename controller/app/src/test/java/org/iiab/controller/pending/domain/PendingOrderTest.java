/*
 * ============================================================================
 * Name        : PendingOrderTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : JVM unit tests for PendingOrder.DISPLAY_ORDER (ADFA-5169).
 * ============================================================================
 */
package org.iiab.controller.pending.domain;

import static org.junit.Assert.assertEquals;

import org.iiab.controller.system.domain.ContentType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PendingOrderTest {

    @Test
    public void displayOrderGroupsByTypeThenName() {
        PendingOrder zimEs = new PendingOrder(ContentType.ZIM, "z2", "Wikipedia (Spanish)", 3);
        PendingOrder zimEn = new PendingOrder(ContentType.ZIM, "z1", "Wikipedia (English)", 4);
        PendingOrder book = new PendingOrder(ContentType.BOOKS, "b1", "Gutenberg", 1);
        PendingOrder course = new PendingOrder(ContentType.COURSES, "c1", "Khan Academy", 12);

        List<PendingOrder> orders = new ArrayList<>(Arrays.asList(course, book, zimEs, zimEn));
        orders.sort(PendingOrder.DISPLAY_ORDER);

        // ZIM group first (English before Spanish), then Books, then Courses.
        assertEquals(Arrays.asList("z1", "z2", "b1", "c1"),
                Arrays.asList(orders.get(0).id(), orders.get(1).id(),
                        orders.get(2).id(), orders.get(3).id()));
    }

    @Test
    public void nameIsCaseInsensitiveWithinAType() {
        PendingOrder lower = new PendingOrder(ContentType.BOOKS, "b1", "atlas", 0);
        PendingOrder upper = new PendingOrder(ContentType.BOOKS, "b2", "Beowulf", 0);

        List<PendingOrder> orders = new ArrayList<>(Arrays.asList(upper, lower));
        orders.sort(PendingOrder.DISPLAY_ORDER);

        assertEquals("b1", orders.get(0).id());   // "atlas" before "Beowulf" regardless of case
    }

    @Test
    public void nullNameSortsAsEmptyAndDoesNotThrow() {
        PendingOrder noName = new PendingOrder(ContentType.ZIM, "z1", null, 0);
        PendingOrder named = new PendingOrder(ContentType.ZIM, "z2", "B", 0);

        List<PendingOrder> orders = new ArrayList<>(Arrays.asList(named, noName));
        orders.sort(PendingOrder.DISPLAY_ORDER);   // must not throw

        assertEquals("z1", orders.get(0).id());    // empty name sorts first
    }
}
