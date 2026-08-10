/*
 * ============================================================================
 * Name        : PendingContent.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads, in one pass, what content a run is carrying (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.kolibri.data.KolibriWishlist;
import org.iiab.controller.kolibri.presentation.KolibriSeedRepository;
import org.iiab.controller.kolibri.presentation.KolibriSeedState;
import org.iiab.controller.redesign.BooksDownloadService;
import org.iiab.controller.redesign.BooksWishlist;
import org.iiab.controller.redesign.MapsWishlist;
import org.iiab.controller.redesign.ZimDownloadService;
import org.iiab.controller.redesign.ZimWishlist;
import org.iiab.controller.system.domain.ContentType;

/**
 * Reads what content this run is carrying — all types, in one pass.
 *
 * <p><b>Which types exist, and which of them are live, is not decided here.</b> That
 * belongs to {@link ContentType} in the domain, where it is a plain enum and can be
 * unit-tested without a device. This class only knows <em>where each fact is stored
 * on Android</em>: a wishlist in SharedPreferences, a static on a running service, an
 * observable repository.
 *
 * <p><b>Read once, decide from the snapshot.</b> Every value here can change under
 * the caller: the wishlists are re-parsed from JSON on each access, and the session
 * states are published from service callbacks. A screen that asks the same question
 * twice in one pass can get two answers and contradict itself — which is precisely
 * what happened when {@code SetupProgressActivity} drew a Courses row from one read
 * and computed completion from another. So the entry point is {@link #read} and
 * everything is derived from the returned {@link Snapshot}. The convenience methods
 * that take a {@code Context} are for callers that ask exactly one question at one
 * moment, and each of them is a fresh snapshot.
 *
 * <p><b>Known layering debt.</b> {@link KolibriSeedRepository} and the two download
 * services live in presentation packages, so this data-layer class reaches upward to
 * consult them. That inversion predates this class ({@code ContentStateInvalidator}
 * has it too) and is not worth a private fix here: it disappears when the session
 * state moves behind the operation model (ADR-5061). Keeping the policy in
 * {@link ContentType} is what stops that debt from spreading.
 */
public final class PendingContent {

    private static final String TAG = "K2Go-PendingContent";

    private PendingContent() {
    }

    /**
     * One consistent reading of every content type: what is banked, and what is
     * already running.
     *
     * <p>Immutable, and cheap to hold for the length of a render pass.
     */
    public static final class Snapshot {

        private final int zimBanked;
        private final int booksBanked;
        private final int coursesBanked;
        private final boolean mapsBanked;
        private final boolean zimSession;
        private final boolean booksSession;
        private final KolibriSeedState courses;

        private Snapshot(int zimBanked, int booksBanked, int coursesBanked, boolean mapsBanked,
                         boolean zimSession, boolean booksSession, KolibriSeedState courses) {
            this.zimBanked = zimBanked;
            this.booksBanked = booksBanked;
            this.coursesBanked = coursesBanked;
            this.mapsBanked = mapsBanked;
            this.zimSession = zimSession;
            this.booksSession = booksSession;
            this.courses = courses;
        }

        /** How many orders of this type are waiting. Maps has no per-item count. */
        public int banked(ContentType type) {
            switch (type) {
                case ZIM: return zimBanked;
                case BOOKS: return booksBanked;
                case COURSES: return coursesBanked;
                case MAPS: return mapsBanked ? 1 : 0;
                default: return 0;
            }
        }

        /** Whether a stream for this type is already running. Never true for Maps,
         *  whose progress belongs to the module queue rather than to a stream. */
        public boolean hasSession(ContentType type) {
            switch (type) {
                case ZIM: return zimSession;
                case BOOKS: return booksSession;
                case COURSES: return courses.hasSession();
                default: return false;
            }
        }

        /** Anything of this type in play at all. */
        public boolean inPlay(ContentType type) {
            return hasSession(type) || banked(type) > 0;
        }

        /**
         * Any content order waiting, of any type — the question "should the
         * post-install screen open at all?". Maps counts: the user chose it.
         */
        public boolean anyBanked() {
            for (ContentType t : ContentType.values()) {
                if (banked(t) > 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Any <b>live</b> content in play: a REST stream running, or an order for one
         * not yet handed over. Maps is excluded by its class, not by a special case —
         * the question "can this run finish on the proot queue alone?" is the negation
         * of this one.
         */
        public boolean anyLive() {
            return anyLiveOtherThan(null);
        }

        /**
         * {@link #anyLive} ignoring one stream, for "is this the only live thing
         * happening?" — asked by a screen that has just started a stream and wants to
         * know whether to open its detail or stay on the index. The stream just
         * confirmed may not have registered a session yet, so it is excluded by name.
         *
         * @param key a {@link ContentType#key()}. An unrecognised name (or
         *            {@code null}) excludes nothing, which keeps the caller on the
         *            index — the safe side of this question.
         */
        public boolean anyLiveOtherThan(String key) {
            ContentType excluded = ContentType.byKey(key);
            for (ContentType t : ContentType.values()) {
                if (t.isLive() && t != excluded && inPlay(t)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether a content stream is <b>actually in flight</b> right now.
         *
         * <p>Deliberately narrower than {@link #anyLive}, which also counts an order that
         * is banked and waiting. The question here is "is there something to go back
         * to?", asked by the Home header that offers the way into the install index. A
         * banked order that has not started has no screen to show and, if its drain is
         * blocked, would leave the header claiming work forever.
         */
        public boolean anyRunning() {
            for (ContentType t : ContentType.values()) {
                if (t.isLive() && hasSession(t)) {
                    return true;
                }
            }
            return false;
        }

        /** How many orders are banked in total. For logging and copy, never for a
         *  decision — a decision should ask {@link #anyBanked}. */
        public int bankedCount() {
            int n = 0;
            for (ContentType t : ContentType.values()) {
                n += banked(t);
            }
            return n;
        }

        /** The Courses session as it was at snapshot time. Callers that draw a row
         *  and then judge completion must use this one object for both. */
        public KolibriSeedState courses() {
            return courses;
        }
    }

    /**
     * Reads every content type once. Safe to call on the main thread: four
     * SharedPreferences reads and three field reads.
     */
    public static Snapshot read(Context ctx) {
        if (ctx == null) {
            return new Snapshot(0, 0, 0, false, false, false, KolibriSeedRepository.get().current());
        }
        Context app = ctx.getApplicationContext();
        int zim = 0, books = 0, courses = 0;
        boolean maps = false;
        try {
            zim = ZimWishlist.size(app);
            books = BooksWishlist.size(app);
            courses = KolibriWishlist.size(app);
            maps = MapsWishlist.has(app);
        } catch (Exception e) {
            // A wishlist that will not parse must not take a screen down with it; the
            // worst case is that we under-report and the drain finds it on a later pass.
            Log.w(TAG, "could not read a wishlist: " + e.getMessage());
        }
        return new Snapshot(zim, books, courses, maps,
                ZimDownloadService.hasSession(), BooksDownloadService.hasSession(),
                KolibriSeedRepository.get().current());
    }

    /** One-shot: is any content order waiting? */
    public static boolean anyBanked(Context ctx) {
        return read(ctx).anyBanked();
    }

    /** One-shot: is any live content in play other than {@code key}? */
    public static boolean anyLiveOtherThan(Context ctx, String key) {
        return read(ctx).anyLiveOtherThan(key);
    }

    /** One-shot: is a content stream actually running right now? */
    public static boolean anyRunning(Context ctx) {
        return read(ctx).anyRunning();
    }

    /**
     * Discards every banked order, of every type.
     *
     * <p>Two callers for the same reason at two moments: a fresh wizard run drops
     * whatever an abandoned earlier run left behind, and a completed system
     * replacement drops the orders placed against the system that is gone. Both used
     * to enumerate the types themselves, and both were missing one.
     */
    public static void clearAll(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        ZimWishlist.clear(app);
        BooksWishlist.clear(app);
        KolibriWishlist.clear(app);
        MapsWishlist.clear(app);
    }
}
