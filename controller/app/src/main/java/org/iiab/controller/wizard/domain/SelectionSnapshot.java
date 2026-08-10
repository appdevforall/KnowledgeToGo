/*
 * ============================================================================
 * Name        : SelectionSnapshot.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Flattening the wizard's carts to arrays and back, so they can be
 *               written to saved state. Pure JVM, no Android (ADFA-5061).
 * ============================================================================
 */
package org.iiab.controller.wizard.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the wizard's carts into flat arrays and back.
 *
 * <p>Saved state is a {@code Bundle}, which takes arrays but not a map of arrays, so
 * the carts have to be flattened. That flattening is where this kind of code goes
 * wrong — a value array of the wrong length, a key list out of step with its values,
 * an entry silently dropped — and none of it is visible until a user comes back to a
 * cart that lost one item. So it lives here, as plain Java over plain arrays, and is
 * unit-tested; the {@code Bundle} itself is assembled by the view model and is thin
 * enough to read at a glance.
 *
 * <p><b>Order matters.</b> Both carts are {@code LinkedHashMap} because the review
 * screens list entries in the order they were picked. Round-tripping preserves it.
 *
 * <p><b>Fails closed.</b> If the arrays do not agree with each other, nothing is
 * restored rather than half of it. A cart that comes back empty is what happens today
 * anyway; a cart that comes back with a title attached to the wrong book is worse, and
 * the user has no way to tell.
 */
public final class SelectionSnapshot {

    /** A Books entry is {title, author, download_url}. */
    public static final int BOOK_FIELDS = 3;

    private SelectionSnapshot() {
    }

    public static String[] keys(Map<String, ?> cart) {
        if (cart == null) {
            return new String[0];
        }
        return cart.keySet().toArray(new String[0]);
    }

    /** The ZIM sizes, in the same order as {@link #keys}. */
    public static long[] sizes(Map<String, Long> cart) {
        if (cart == null) {
            return new long[0];
        }
        long[] out = new long[cart.size()];
        int i = 0;
        for (Long v : cart.values()) {
            out[i++] = v == null ? 0L : v;
        }
        return out;
    }

    /**
     * One column of the Books cart — 0 title, 1 author, 2 url — in key order.
     *
     * <p>A short or null value array yields empty strings rather than a hole, so the
     * columns always line up with the keys.
     */
    public static String[] bookColumn(Map<String, String[]> cart, int field) {
        if (cart == null) {
            return new String[0];
        }
        String[] out = new String[cart.size()];
        int i = 0;
        for (String[] v : cart.values()) {
            out[i++] = (v != null && field < v.length && v[field] != null) ? v[field] : "";
        }
        return out;
    }

    /**
     * Refills a ZIM cart from a round trip.
     *
     * @return true when the arrays agreed and the cart was filled
     */
    public static boolean restoreZim(LinkedHashMap<String, Long> into, String[] keys, long[] sizes) {
        if (into == null || keys == null || sizes == null || keys.length != sizes.length) {
            return false;
        }
        into.clear();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null) {
                into.put(keys[i], sizes[i]);
            }
        }
        return true;
    }

    /**
     * Refills a Books cart from a round trip.
     *
     * @return true when every column agreed with the keys and the cart was filled
     */
    public static boolean restoreBooks(LinkedHashMap<String, String[]> into, String[] ids,
                                       String[] titles, String[] authors, String[] urls) {
        if (into == null || ids == null || titles == null || authors == null || urls == null) {
            return false;
        }
        if (ids.length != titles.length || ids.length != authors.length || ids.length != urls.length) {
            return false;
        }
        into.clear();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != null) {
                into.put(ids[i], new String[]{
                        titles[i] == null ? "" : titles[i],
                        authors[i] == null ? "" : authors[i],
                        urls[i] == null ? "" : urls[i]});
            }
        }
        return true;
    }
}
