/*
 * ============================================================================
 * Name        : PendingOrdersRepositoryImplTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Instrumented test for ADFA-5169 (finding 6). Validates the banked-
 *               order mechanism end to end at the data layer with real
 *               SharedPreferences: the three content wishlists are listed as
 *               PendingOrders, and cancelling one removes only that order. This is
 *               the deterministic stand-in for a state that is not reachable through
 *               normal UX (see controller/docs/ADFA-5169-pending-downloads-design.md).
 * ============================================================================
 */
package org.appdevforall.k2go.pending.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.appdevforall.k2go.kolibri.data.KolibriWishlist;
import org.appdevforall.k2go.pending.domain.PendingOrder;
import org.appdevforall.k2go.redesign.BooksWishlist;
import org.appdevforall.k2go.redesign.ZimWishlist;
import org.appdevforall.k2go.system.domain.ContentType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class PendingOrdersRepositoryImplTest {

    /** A valid Kolibri channel id: 32 lowercase hex chars (ChannelId.normalise). */
    private static final String CHANNEL = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    @After
    public void clearWishlists() {
        Context c = ctx();
        ZimWishlist.clear(c);
        BooksWishlist.clear(c);
        KolibriWishlist.clear(c);
    }

    private void seedFour() {
        Context c = ctx();
        Map<String, Long> zim = new LinkedHashMap<>();
        zim.put("wikipedia|en|maxi", 4_200L);
        zim.put("wikipedia|es|maxi", 3_100L);
        ZimWishlist.add(c, zim);
        BooksWishlist.add(c, "b1", "Gutenberg", "https://example.invalid/x.epub");
        KolibriWishlist.add(c, CHANNEL, 1, "Khan Academy", 12_000L, null);
    }

    @Test
    public void emptyWhenNothingBanked() {
        assertTrue(new PendingOrdersRepositoryImpl(ctx()).list().isEmpty());
    }

    @Test
    public void listsEveryBankedOrderGroupedByType() {
        seedFour();
        List<PendingOrder> orders = new PendingOrdersRepositoryImpl(ctx()).list();

        assertEquals(4, orders.size());
        // Grouped by content type in enum order: ZIM (2), Books (1), Courses (1).
        assertEquals(ContentType.ZIM, orders.get(0).type());
        assertEquals(ContentType.ZIM, orders.get(1).type());
        assertEquals(ContentType.BOOKS, orders.get(2).type());
        assertEquals(ContentType.COURSES, orders.get(3).type());
        // Names: ZIM uses its selector key; Books uses the title; Courses uses the name.
        assertEquals("wikipedia|en|maxi", orders.get(0).name());
        assertEquals("Gutenberg", orders.get(2).name());
        assertEquals("Khan Academy", orders.get(3).name());
    }

    @Test
    public void cancelRemovesOnlyThatOrder() {
        seedFour();
        PendingOrdersRepositoryImpl repo = new PendingOrdersRepositoryImpl(ctx());

        PendingOrder book = null;
        for (PendingOrder o : repo.list()) {
            if (o.type() == ContentType.BOOKS) book = o;
        }
        assertNotNull(book);
        repo.cancel(book);

        List<PendingOrder> after = repo.list();
        assertEquals(3, after.size());
        for (PendingOrder o : after) {
            assertNotEquals(ContentType.BOOKS, o.type());
        }
        assertEquals(0, BooksWishlist.size(ctx()));   // the Books order is gone
        assertEquals(2, ZimWishlist.size(ctx()));      // the rest are untouched
        assertEquals(1, KolibriWishlist.size(ctx()));
    }

    @Test
    public void cancelOneZimLeavesTheOtherZim() {
        seedFour();
        PendingOrdersRepositoryImpl repo = new PendingOrdersRepositoryImpl(ctx());

        // Exercises the new ZimWishlist.remove(key): drop only the English collection.
        repo.cancel(new PendingOrder(ContentType.ZIM, "wikipedia|en|maxi", "x", 0L));

        List<PendingOrder> after = repo.list();
        assertEquals(3, after.size());
        assertEquals(1, ZimWishlist.size(ctx()));
        boolean spanishRemains = false;
        for (PendingOrder o : after) {
            if ("wikipedia|es|maxi".equals(o.id())) spanishRemains = true;
        }
        assertTrue(spanishRemains);
    }
}
