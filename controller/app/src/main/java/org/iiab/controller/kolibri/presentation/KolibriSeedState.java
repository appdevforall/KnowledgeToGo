/*
 * ============================================================================
 * Name        : KolibriSeedState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable snapshot of the Kolibri seeding session, published by
 *               the service and observed by the screens (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What is being seeded right now, as one immutable value.
 *
 * <p>Mirrors {@code ModuleQueueState}: the service is the only writer, screens
 * observe it through {@link KolibriSeedRepository}, and every published value is
 * a fresh snapshot. That is the deliberate difference from the other three
 * content services, which keep the same shape in mutable {@code static} arrays —
 * see ADR-4954 D7 for why this one does not.
 *
 * <p>Per-item state lives in {@link Item} rather than in parallel arrays. The
 * parallel-array form in {@code ZimDownloadService} exists because
 * {@code ProvisioningChecklist} takes an {@code int[]}; {@link #statusOrdinals()}
 * produces that on demand, so the checklist still works without the state having
 * to be shaped around it.
 */
public final class KolibriSeedState {

    /** Per-item progress. The order matches the order the user queued them. */
    public enum Status {
        /** Queued, not started. Must stay ordinal 0: {@code ProvisioningChecklist} treats 0 as pending. */
        PENDING,
        /** Downloading or importing. */
        ACTIVE,
        /** Finished successfully. */
        DONE,
        /** Gave up after the service's retry budget. The batch continues. */
        FAILED
    }

    /** One queued channel. Immutable. */
    public static final class Item {
        private final String channelId;
        private final String label;
        private final long bytes;
        private final List<String> nodeIds;
        private final Status status;
        private final int percent;

        Item(String channelId, String label, long bytes, List<String> nodeIds,
             Status status, int percent) {
            this.channelId = channelId;
            this.label = label == null ? "" : label;
            this.bytes = Math.max(0L, bytes);
            this.nodeIds = nodeIds == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(nodeIds));
            this.status = status == null ? Status.PENDING : status;
            this.percent = percent;
        }

        /** The whole channel. */
        public static Item pending(String channelId, String label, long bytes) {
            return pending(channelId, label, bytes, null);
        }

        /**
         * @param nodeIds subtree roots to import, or null/empty for the whole channel
         */
        public static Item pending(String channelId, String label, long bytes,
                                   List<String> nodeIds) {
            return new Item(channelId, label, bytes, nodeIds, Status.PENDING, 0);
        }

        public String channelId() {
            return channelId;
        }

        /**
         * Subtree roots to import; empty means the whole channel.
         *
         * <p>Carried on the item rather than held by the service on purpose. The
         * service stops itself when the queue drains, so a Retry starts a fresh
         * instance — anything kept in a service field is gone by then, and the
         * selection would silently widen to the whole channel. On a 62 GB channel
         * that is tens of gigabytes nobody asked for, with nothing failing to
         * signal it.
         */
        public List<String> nodeIds() {
            return nodeIds;
        }

        /** True when the whole channel was queued rather than selected subtrees. */
        public boolean isWholeChannel() {
            return nodeIds.isEmpty();
        }

        /** What the checklist row shows. Never null. */
        public String label() {
            return label;
        }

        /** Published size, or 0 when unknown. Used to weight overall progress. */
        public long bytes() {
            return bytes;
        }

        public Status status() {
            return status;
        }

        /** 0-100, or -1 when the source reported no figure. */
        public int percent() {
            return percent;
        }

        public boolean isTerminal() {
            return status == Status.DONE || status == Status.FAILED;
        }

        Item with(Status s, int pct) {
            return new Item(channelId, label, bytes, nodeIds, s, pct);
        }
    }

    private static final KolibriSeedState IDLE =
            new KolibriSeedState(Collections.<Item>emptyList(), false, 0, 0L, 0L);

    private final List<Item> items;
    private final boolean running;
    private final int index;
    private final long speedBytesPerSec;
    private final long seq;

    private KolibriSeedState(List<Item> items, boolean running, int index,
                             long speedBytesPerSec, long seq) {
        this.items = items;
        this.running = running;
        this.index = index;
        this.speedBytesPerSec = speedBytesPerSec;
        this.seq = seq;
    }

    /** No session at all. */
    public static KolibriSeedState idle() {
        return IDLE;
    }

    /** A fresh session with everything pending. */
    public static KolibriSeedState of(List<Item> queued) {
        if (queued == null || queued.isEmpty()) {
            return IDLE;
        }
        List<Item> copy = new ArrayList<>();
        for (Item i : queued) {
            if (i != null) {
                copy.add(i);
            }
        }
        if (copy.isEmpty()) {
            return IDLE;
        }
        return new KolibriSeedState(Collections.unmodifiableList(copy), false, 0, 0L, 0L);
    }

    /** Items in queue order. Unmodifiable, never null. */
    public List<Item> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    /** True when a session exists, whether or not it is currently moving. */
    public boolean hasSession() {
        return !items.isEmpty();
    }

    /** True while the service is actively working an item. */
    public boolean isRunning() {
        return running;
    }

    /** Index of the item in flight. Meaningless when {@link #isRunning()} is false. */
    public int index() {
        return index;
    }

    /** Bytes per second for the item in flight, or 0. */
    public long speedBytesPerSec() {
        return speedBytesPerSec;
    }

    /** Monotonic counter, so a screen can fire a one-shot effect exactly once. */
    public long seq() {
        return seq;
    }

    /** Every item is terminal and nothing is in flight. */
    public boolean isComplete() {
        if (items.isEmpty() || running) {
            return false;
        }
        for (Item i : items) {
            if (!i.isTerminal()) {
                return false;
            }
        }
        return true;
    }

    public int failedCount() {
        int n = 0;
        for (Item i : items) {
            if (i.status() == Status.FAILED) {
                n++;
            }
        }
        return n;
    }

    /** The item in flight, or null. */
    public Item current() {
        return running && index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /**
     * Overall progress, 0-100, weighted by published size.
     *
     * <p>Weighted rather than "items done over items total" because Kolibri
     * channels differ by orders of magnitude — the catalog runs from a few MB to
     * 62 GB — so counting items would show a bar that sits still for an hour and
     * then jumps. Falls back to counting items when no size is known, which is
     * the only honest option then.
     */
    public int overallPercent() {
        if (items.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (Item i : items) {
            total += i.bytes();
        }
        if (total <= 0L) {
            int terminal = 0;
            for (Item i : items) {
                if (i.isTerminal()) {
                    terminal++;
                }
            }
            return terminal * 100 / items.size();
        }
        long done = 0L;
        for (Item i : items) {
            if (i.isTerminal()) {
                done += i.bytes();
            } else if (i.status() == Status.ACTIVE && i.percent() > 0) {
                done += i.bytes() * i.percent() / 100L;
            }
        }
        return (int) Math.min(100L, done * 100L / total);
    }

    /**
     * Statuses as ordinals, for {@code ProvisioningChecklist}, which predates
     * this type and takes an {@code int[]}.
     */
    public int[] statusOrdinals() {
        int[] out = new int[items.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = items.get(i).status().ordinal();
        }
        return out;
    }

    // ---- transitions: each returns a new snapshot -------------------------

    KolibriSeedState startingItem(int i) {
        if (i < 0 || i >= items.size()) {
            return this;
        }
        List<Item> next = new ArrayList<>(items);
        next.set(i, next.get(i).with(Status.ACTIVE, 0));
        return new KolibriSeedState(Collections.unmodifiableList(next), true, i, 0L, seq);
    }

    KolibriSeedState progress(int i, int percent, long speed) {
        if (i < 0 || i >= items.size()) {
            return this;
        }
        List<Item> next = new ArrayList<>(items);
        next.set(i, next.get(i).with(Status.ACTIVE, percent));
        return new KolibriSeedState(Collections.unmodifiableList(next), true, i,
                Math.max(0L, speed), seq);
    }

    KolibriSeedState finishItem(int i, boolean ok) {
        if (i < 0 || i >= items.size()) {
            return this;
        }
        List<Item> next = new ArrayList<>(items);
        next.set(i, next.get(i).with(ok ? Status.DONE : Status.FAILED, ok ? 100 : 0));
        return new KolibriSeedState(Collections.unmodifiableList(next), running, i, 0L, seq);
    }

    /** Re-queue a failed item so the service can pick it up again. */
    KolibriSeedState retry(int i) {
        if (i < 0 || i >= items.size() || items.get(i).status() != Status.FAILED) {
            return this;
        }
        List<Item> next = new ArrayList<>(items);
        next.set(i, next.get(i).with(Status.PENDING, 0));
        return new KolibriSeedState(Collections.unmodifiableList(next), running, index, speedBytesPerSec, seq);
    }

    KolibriSeedState stopped() {
        return new KolibriSeedState(items, false, index, 0L, seq);
    }

    KolibriSeedState withSeq(long s) {
        return new KolibriSeedState(items, running, index, speedBytesPerSec, s);
    }

    /** Index of the first item still waiting, or -1. */
    public int firstPending() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).status() == Status.PENDING) {
                return i;
            }
        }
        return -1;
    }
}
