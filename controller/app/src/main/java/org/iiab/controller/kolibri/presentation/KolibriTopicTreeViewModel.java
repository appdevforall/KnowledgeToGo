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

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.GetTopicTreeUseCase;
import org.iiab.controller.kolibri.domain.TopicNode;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Navigation state for the topic picker: one channel, one path, one level shown.
 *
 * <p>Deliberately narrow. It does <em>not</em> own what the user picked — that is
 * {@link KolibriCatalogViewModel}, which already owns "the selection" and has to
 * survive the trip to the confirm screen. This one owns only where in the tree the
 * user is, and it delegates even that bookkeeping to {@link TopicTreeCursor} so the
 * path, the level cache and the fetch ticket can be tested without a looper.
 *
 * <p><b>Everything is published on the main thread.</b> Not for thread-safety —
 * {@code postValue} is safe — but because the ticket check and the publish have to
 * be one indivisible step. Checking on the IO thread and then handing the value to
 * {@code postValue} leaves a window where the user navigates in between, and a
 * pending {@code postValue} delivered after a {@code setValue} overwrites it. That
 * exact interleaving is what went wrong in {@code KolibriSeedRepository}, so here
 * the answer is carried to the main looper and re-checked there.
 */
public class KolibriTopicTreeViewModel extends ViewModel {

    /** Enough for a deep browse; a Kolibri tree is rarely more than a few levels. */
    private static final int MAX_CACHED_LEVELS = 32;

    private final GetTopicTreeUseCase getTree;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<KolibriTopicsUiState> state = new MutableLiveData<>();
    private final TopicTreeCursor cursor = new TopicTreeCursor(MAX_CACHED_LEVELS);

    private String channelId = "";

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
        if (cursor.isOn(channel.id())) {
            load();
            return;
        }
        channelId = channel.id();
        cursor.reset(channel.id(), channel.rootNodeId(), channel.name());
        load();
    }

    /** Drills into a topic. Ignored for a node with nothing under it. */
    public void enter(TopicNode topic) {
        if (topic == null || topic.isLeaf()) {
            return;
        }
        cursor.push(topic.id(), topic.title());
        load();
    }

    /**
     * Goes back one level.
     *
     * @return false when already at the channel root, so the caller can let the
     *         gesture fall through to leaving the screen
     */
    public boolean up() {
        if (!cursor.pop()) {
            return false;
        }
        load();
        return true;
    }

    /** Re-reads the current level, forgetting any cached copy of it. */
    public void retry() {
        String id = cursor.currentId();
        if (id == null) {
            return;
        }
        cursor.forget(id);
        load();
    }

    /**
     * Shows the current level, from the cache when it is there and from Studio when
     * it is not. Must be called on the main thread.
     */
    private void load() {
        final String id = cursor.currentId();
        if (id == null) {
            return;
        }
        final String levelTitle = cursor.currentTitle();
        final List<String> ancestors = cursor.ancestorIds();
        final List<String> trail = cursor.trail();

        TopicNode hit = cursor.cached(id);
        if (hit != null) {
            // A cached level is not a fetch, so it takes no ticket — but it does
            // make any fetch still in flight stale, or that answer would replace
            // the level the user just came back to.
            cursor.begin();
            state.setValue(KolibriTopicsUiState.level(hit, levelTitle, ancestors, trail));
            return;
        }

        final long mine = cursor.begin();
        state.setValue(KolibriTopicsUiState.loading(levelTitle, trail));
        executor.execute(() -> {
            final GetTopicTreeUseCase.Result r = getTree.execute(id);
            // Check and publish in one step, on the thread that publishes.
            main.post(() -> {
                if (!cursor.isCurrent(mine)) {
                    return;
                }
                if (r.isUnavailable()) {
                    state.setValue(KolibriTopicsUiState.unavailable(levelTitle, trail));
                    return;
                }
                cursor.remember(id, r.node());
                state.setValue(
                        KolibriTopicsUiState.level(r.node(), levelTitle, ancestors, trail));
            });
        });
    }

    @Override
    protected void onCleared() {
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }
}
