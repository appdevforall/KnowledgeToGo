/*
 * ============================================================================
 * Name        : TopicTreeCursor.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Where the topic picker is standing, what it has already read, and
 *               which fetch is the current one. No Android, so it is unit-testable
 *               on a plain JVM (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import org.iiab.controller.kolibri.domain.TopicNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bookkeeping behind {@link KolibriTopicTreeViewModel}: a path, a cache of the
 * levels already read, and a ticket that says which fetch is still wanted.
 *
 * <p>Extracted from the view model for one reason: these three are the fragile part
 * of browsing a tree over a slow connection, and they were the part with no test —
 * a view model that publishes through {@code LiveData} needs a main looper, so
 * covering them there means Robolectric. Here they are plain objects.
 *
 * <p><b>Why the ticket exists.</b> Fetches are not cancellable, so a request the
 * user has navigated away from still completes. Publishing its answer would land an
 * old level on top of the one they are now looking at. Every fetch takes a ticket
 * from {@link #begin()} and the answer is only published while
 * {@link #isCurrent(long)} still agrees — and that check has to happen on the same
 * thread as the publish, or it only narrows the window instead of closing it.
 *
 * <p><b>Why the cache exists.</b> Going back up must not cost another round trip,
 * and a tree does not change while a wizard screen is open. Capped, because a deep
 * browse should not grow without bound on a phone; the oldest level goes first.
 *
 * <p>Every method is {@code synchronized}: the path and the ticket are touched from
 * the main thread, the cache is written from an IO thread and read from the main
 * one.
 */
final class TopicTreeCursor {

    /** One step of the path: the node, and the label the breadcrumb shows for it. */
    private static final class Crumb {
        final String id;
        final String title;

        Crumb(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private final int maxCachedLevels;
    private final Map<String, TopicNode> cache = new LinkedHashMap<>();
    private final List<Crumb> path = new ArrayList<>();

    private String channelId = "";
    private long ticket = 0L;

    TopicTreeCursor(int maxCachedLevels) {
        this.maxCachedLevels = Math.max(1, maxCachedLevels);
    }

    // ---- identity ---------------------------------------------------------

    /** True when this cursor is already walking {@code id} and has somewhere to be. */
    synchronized boolean isOn(String id) {
        return id != null && id.equals(channelId) && !path.isEmpty();
    }

    /**
     * Points the cursor at a new channel: drops the path and the cache, and
     * invalidates anything in flight so a late answer for the old tree cannot
     * repopulate the cache of a tree we have left.
     */
    synchronized void reset(String newChannelId, String rootId, String rootTitle) {
        channelId = newChannelId == null ? "" : newChannelId;
        ticket++;
        cache.clear();
        path.clear();
        path.add(new Crumb(rootId, rootTitle));
    }

    // ---- navigation -------------------------------------------------------

    synchronized boolean isStarted() {
        return !path.isEmpty();
    }

    synchronized void push(String id, String title) {
        path.add(new Crumb(id, title));
    }

    /** Steps back up. False when already at the root, so the caller can leave. */
    synchronized boolean pop() {
        if (path.size() <= 1) {
            return false;
        }
        path.remove(path.size() - 1);
        return true;
    }

    synchronized String currentId() {
        return path.isEmpty() ? null : path.get(path.size() - 1).id;
    }

    synchronized String currentTitle() {
        return path.isEmpty() ? "" : path.get(path.size() - 1).title;
    }

    /** Ids above the current level, outermost first. Empty at the root. */
    synchronized List<String> ancestorIds() {
        if (path.size() <= 1) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(path.size() - 1);
        for (int i = 0; i < path.size() - 1; i++) {
            out.add(path.get(i).id);
        }
        return out;
    }

    /** Titles of the whole path, for the breadcrumb. */
    synchronized List<String> trail() {
        List<String> out = new ArrayList<>(path.size());
        for (Crumb c : path) {
            out.add(c.title);
        }
        return out;
    }

    // ---- the ticket -------------------------------------------------------

    /** Takes the ticket for a new fetch, making every earlier one stale. */
    synchronized long begin() {
        return ++ticket;
    }

    /** True while {@code t} is still the fetch whose answer is wanted. */
    synchronized boolean isCurrent(long t) {
        return t == ticket;
    }

    // ---- the level cache --------------------------------------------------

    synchronized TopicNode cached(String id) {
        return id == null ? null : cache.get(id);
    }

    synchronized void forget(String id) {
        cache.remove(id);
    }

    /** Caches a level, evicting the oldest once the cap is reached. */
    synchronized void remember(String id, TopicNode node) {
        if (id == null || node == null) {
            return;
        }
        if (!cache.containsKey(id) && cache.size() >= maxCachedLevels) {
            Iterator<String> it = cache.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        cache.put(id, node);
    }

    synchronized int cachedLevels() {
        return cache.size();
    }
}
