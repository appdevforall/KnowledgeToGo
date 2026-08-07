/*
 * ============================================================================
 * Name        : KolibriTopicTreeViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Walks one channel's topic tree for the picker: which level is on
 *               screen, how it was reached, and whether it arrived (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.GetTopicTreeUseCase;
import org.iiab.controller.kolibri.domain.TopicNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Navigation state for the topic picker: one channel, one path, one level shown.
 *
 * <p>Deliberately narrow. It does <em>not</em> own what the user picked — that is
 * {@link KolibriCatalogViewModel}, which already owns "the selection" and has to
 * survive the trip to the confirm screen. This one owns only where in the tree the
 * user is, so the two can be reasoned about separately.
 *
 * <p>Two things here exist because of the network rather than the design:
 *
 * <ul>
 *   <li><b>Levels are cached.</b> Going back up must not re-fetch: on the
 *       connections this product targets a round trip is seconds, and the tree
 *       does not change while a wizard screen is open. The cache is capped so a
 *       deep browse cannot grow without bound on a phone.</li>
 *   <li><b>Late answers are dropped.</b> Each fetch carries a ticket; if the user
 *       has navigated since, the answer is discarded instead of published. Without
 *       this a slow request lands on top of the level the user is now looking
 *       at — the same class of bug as the {@code postValue} race already fixed in
 *       {@code KolibriSeedRepository}.</li>
 * </ul>
 */
public class KolibriTopicTreeViewModel extends ViewModel {

    /** Enough for a deep browse; a Kolibri tree is rarely more than a few levels. */
    private static final int MAX_CACHED_LEVELS = 32;

    /** One step of the path: the node and the label the breadcrumb shows for it. */
    private static final class Crumb {
        final String id;
        final String title;

        Crumb(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private final GetTopicTreeUseCase getTree;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<KolibriTopicsUiState> state = new MutableLiveData<>();

    private final Map<String, TopicNode> cache = new LinkedHashMap<>();
    private final List<Crumb> path = new ArrayList<>();

    private String channelId = "";
    /** Written on the main thread, read on the executor — hence volatile. */
    private volatile long ticket = 0L;

    public KolibriTopicTreeViewModel(GetTopicTreeUseCase getTree) {
        this.getTree = getTree;
    }

    public LiveData<KolibriTopicsUiState> state() {
        return state;
    }

    /** The channel currently being browsed, or empty before the first open. */
    public String channelId() {
        return channelId;
    }

    /**
     * Points the picker at a channel's root.
     *
     * <p>Re-opening the same channel keeps the path and the cache, so returning
     * from the confirm screen puts the user back where they were instead of at the
     * top of a tree they had already walked into.
     */
    public void open(Channel channel) {
        if (channel == null) {
            return;
        }
        if (channel.id().equals(channelId) && !path.isEmpty()) {
            publishCurrent();
            return;
        }
        channelId = channel.id();
        // Invalidate any in-flight answer for the previous channel before dropping
        // its levels, or a late reply could repopulate the cache of a tree we left.
        ticket++;
        clearCache();
        path.clear();
        path.add(new Crumb(channel.rootNodeId(), channel.name()));
        load();
    }

    /** Drills into a topic. Ignored for a node with nothing under it. */
    public void enter(TopicNode topic) {
        if (topic == null || topic.isLeaf()) {
            return;
        }
        path.add(new Crumb(topic.id(), topic.title()));
        load();
    }

    /**
     * Goes back one level.
     *
     * @return false when already at the channel root, so the caller can let the
     *         gesture fall through to leaving the screen
     */
    public boolean up() {
        if (path.size() <= 1) {
            return false;
        }
        path.remove(path.size() - 1);
        load();
        return true;
    }

    /** Re-reads the current level, forgetting any cached copy of it. */
    public void retry() {
        if (path.isEmpty()) {
            return;
        }
        forget(current().id);
        load();
    }

    /** Ids above the level on screen, outermost first. Empty at the root. */
    private List<String> ancestorsOfCurrent() {
        if (path.size() <= 1) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            out.add(path.get(i).id);
        }
        return out;
    }

    private List<String> trail() {
        List<String> out = new ArrayList<>();
        for (Crumb c : path) {
            out.add(c.title);
        }
        return out;
    }

    private Crumb current() {
        return path.get(path.size() - 1);
    }

    /** Publishes the cached level without touching the network. */
    private void publishCurrent() {
        TopicNode cached = cached(current().id);
        if (cached != null) {
            state.setValue(KolibriTopicsUiState.level(
                    cached, current().title, ancestorsOfCurrent(), trail()));
        } else {
            load();
        }
    }

    private void load() {
        final Crumb here = current();
        final List<String> ancestors = ancestorsOfCurrent();
        final List<String> trail = trail();

        TopicNode cached = cached(here.id);
        if (cached != null) {
            state.setValue(KolibriTopicsUiState.level(cached, here.title, ancestors, trail));
            return;
        }

        final long mine = ++ticket;
        state.setValue(KolibriTopicsUiState.loading(here.title, trail));
        executor.execute(() -> {
            GetTopicTreeUseCase.Result r = getTree.execute(here.id);
            // The user may have moved on while this was in flight; a stale answer
            // must not overwrite the level they are looking at now.
            if (mine != ticket) {
                return;
            }
            if (r.isUnavailable()) {
                state.postValue(KolibriTopicsUiState.unavailable(here.title, trail));
                return;
            }
            remember(here.id, r.node());
            state.postValue(KolibriTopicsUiState.level(
                    r.node(), here.title, ancestors, trail));
        });
    }

    // The cache is written on the executor and read on the main thread, so every
    // access goes through these three and nothing touches the map directly.

    private synchronized TopicNode cached(String id) {
        return cache.get(id);
    }

    private synchronized void forget(String id) {
        cache.remove(id);
    }

    private synchronized void clearCache() {
        cache.clear();
    }

    /** Caches a level, evicting the oldest once the cap is reached. */
    private synchronized void remember(String id, TopicNode node) {
        if (node == null) {
            return;
        }
        if (cache.size() >= MAX_CACHED_LEVELS) {
            java.util.Iterator<String> it = cache.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        cache.put(id, node);
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
