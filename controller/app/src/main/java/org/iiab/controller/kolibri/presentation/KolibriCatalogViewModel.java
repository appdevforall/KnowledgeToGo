/*
 * ============================================================================
 * Name        : KolibriCatalogViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Drives the Courses picker: runs the catalog use case off the main
 *               thread and exposes a UI state stream (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.kolibri.domain.CatalogQuery;
import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.GetChannelCatalogUseCase;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The browse screen's state holder.
 *
 * <p>Follows {@code RootfsViewModel}: the screen observes {@link #state()} and
 * never reads the asset or formats a size itself. Reading the bundled catalog
 * parses ~142 JSON lines, which is fast but not instant, so it happens on the
 * executor rather than blocking the first frame.
 *
 * <p>The language list is computed once from the whole catalog and then carried
 * through every subsequent filter. Recomputing it from the filtered result would
 * make the filter delete its own options: pick Spanish and Spanish becomes the
 * only choice left.
 */
public class KolibriCatalogViewModel extends ViewModel {

    private final GetChannelCatalogUseCase getCatalog;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<KolibriCatalogUiState> state =
            new MutableLiveData<>(KolibriCatalogUiState.loading());

    private List<String> allLanguages = Collections.emptyList();
    private java.util.Map<String, String> allLanguageNames = Collections.emptyMap();
    private CatalogQuery query = CatalogQuery.all();

    public KolibriCatalogViewModel(GetChannelCatalogUseCase getCatalog) {
        this.getCatalog = getCatalog;
    }

    public LiveData<KolibriCatalogUiState> state() {
        return state;
    }

    /** The filter currently applied. Never null. */
    public CatalogQuery query() {
        return query;
    }

    /** Loads the catalog unfiltered. Safe to call again; it simply reloads. */
    public void load() {
        apply(CatalogQuery.all());
    }

    /** Re-runs the catalog with a new filter. */
    public void apply(CatalogQuery next) {
        query = next == null ? CatalogQuery.all() : next;
        state.postValue(KolibriCatalogUiState.loading());
        executor.execute(() -> {
            try {
                if (allLanguages.isEmpty()) {
                    allLanguages = getCatalog.availableLanguages();
                    allLanguageNames = getCatalog.languageNames();
                }
                GetChannelCatalogUseCase.Result r = getCatalog.execute(query);
                state.postValue(KolibriCatalogUiState.ready(
                        r.channels(), allLanguages, allLanguageNames, r.generatedOn()));
            } catch (Exception e) {
                state.postValue(KolibriCatalogUiState.error(e.getMessage()));
            }
        });
    }

    /** Keeps the language filter, replaces the keyword. */
    public void search(String keyword) {
        apply(CatalogQuery.of(keyword, query.langCodes()));
    }

    /** Keeps the keyword, replaces the language filter. */
    public void filterLanguage(String langCode) {
        apply(CatalogQuery.of(query.keyword(),
                langCode == null || langCode.isEmpty()
                        ? Collections.<String>emptyList()
                        : Collections.singletonList(langCode)));
    }

    // ---- the selection ----------------------------------------------------
    //
    // Held here rather than as fields on SetupLibraryActivity, which is where the
    // ZIM cart lives. Scoping this ViewModel to the activity gives the same
    // survival across fragment navigation without adding state to a 550-line
    // class that every content feature already has to touch, and CLAUDE.md asks
    // for encapsulated state over more shared mutable fields.

    private final java.util.LinkedHashMap<String, Channel> picked =
            new java.util.LinkedHashMap<>();

    /** Adds or removes a channel. Returns true when it is now selected. */
    public boolean toggle(Channel c) {
        if (c == null) {
            return false;
        }
        if (picked.remove(c.id()) != null) {
            return false;
        }
        picked.put(c.id(), c);
        return true;
    }

    public boolean isPicked(String channelId) {
        return channelId != null && picked.containsKey(channelId);
    }

    /** The chosen channels, in the order they were picked. Unmodifiable. */
    public List<Channel> selection() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(picked.values()));
    }

    public int selectionCount() {
        return picked.size();
    }

    /** Sum of the published sizes of everything chosen. */
    public long selectionBytes() {
        long total = 0L;
        for (Channel c : picked.values()) {
            total += c.publishedSize();
        }
        return total;
    }

    /** True when any chosen channel has no published size, so the total is a floor. */
    public boolean selectionHasUnknownSize() {
        for (Channel c : picked.values()) {
            if (!c.hasKnownSize()) {
                return true;
            }
        }
        return false;
    }

    public void clearSelection() {
        picked.clear();
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
