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

    /**
     * How the list is ordered. A view preference, not a business rule, so it lives
     * here rather than in the use case — and re-sorting never re-reads the asset.
     */
    public enum Sort {
        SIZE_DESC, SIZE_ASC, NAME_ASC, NAME_DESC;

        boolean isSize() {
            return this == SIZE_DESC || this == SIZE_ASC;
        }

        boolean isName() {
            return this == NAME_ASC || this == NAME_DESC;
        }

        Sort flipped() {
            switch (this) {
                case SIZE_DESC: return SIZE_ASC;
                case SIZE_ASC:  return SIZE_DESC;
                case NAME_ASC:  return NAME_DESC;
                default:        return NAME_ASC;
            }
        }
    }

    private List<String> allLanguages = Collections.emptyList();
    private java.util.Map<String, String> allLanguageNames = Collections.emptyMap();
    private CatalogQuery query = CatalogQuery.all();
    private Sort sort = Sort.NAME_ASC;

    /** The last filtered result, unsorted, so a sort tap costs nothing but a sort. */
    private List<Channel> lastResult = Collections.emptyList();
    private String lastGeneratedOn = "";

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
                lastResult = r.channels();
                lastGeneratedOn = r.generatedOn();
                state.postValue(KolibriCatalogUiState.ready(
                        sorted(), allLanguages, allLanguageNames, lastGeneratedOn));
            } catch (Exception e) {
                state.postValue(KolibriCatalogUiState.error(e.getMessage()));
            }
        });
    }

    public Sort sort() {
        return sort;
    }

    /**
     * Reorders what is already on screen. Called from a click listener, so the
     * result is published with {@code setValue} on the main thread; ~142 rows makes
     * the sort itself immeasurable, and it avoids a pointless re-read of the asset.
     */
    public void setSort(Sort next) {
        if (next == null || next == sort) {
            return;
        }
        sort = next;
        KolibriCatalogUiState current = state.getValue();
        if (current != null && !current.isLoading() && !current.hasError()) {
            state.setValue(KolibriCatalogUiState.ready(
                    sorted(), allLanguages, allLanguageNames, lastGeneratedOn));
        }
    }

    /**
     * The current result in the current order. Channels whose size Studio does not
     * publish sort last in both size directions: they have no position on that axis,
     * and putting them first would read as "smallest".
     */
    private List<Channel> sorted() {
        List<Channel> out = new java.util.ArrayList<>(lastResult);
        Collections.sort(out, (a, b) -> {
            if (sort.isName()) {
                int c = a.name().compareToIgnoreCase(b.name());
                return sort == Sort.NAME_ASC ? c : -c;
            }
            if (a.hasKnownSize() != b.hasKnownSize()) {
                return a.hasKnownSize() ? -1 : 1;
            }
            int c = Long.compare(a.publishedSize(), b.publishedSize());
            return sort == Sort.SIZE_ASC ? c : -c;
        });
        return out;
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
