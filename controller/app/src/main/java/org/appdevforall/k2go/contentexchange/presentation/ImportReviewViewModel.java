/*
 * ============================================================================
 * Name        : ImportReviewViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Reads a bundle from the picked drive (manifest or folder scan), validates it
 *               against this box through the import source, and owns the review selection + the
 *               phone-disk preflight. Off-main-thread load; selection is encapsulated ViewModel state
 *               (indices into the validated list), not a static or a persisted wishlist.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.content.Context;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.contentexchange.data.ImportManifestReader;
import org.appdevforall.k2go.contentexchange.data.SafImportCarrier;
import org.appdevforall.k2go.contentexchange.domain.GroupSelection;
import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ImportManifest;
import org.appdevforall.k2go.contentexchange.domain.ImportPreflight;
import org.appdevforall.k2go.contentexchange.domain.ImportSource;
import org.appdevforall.k2go.contentexchange.domain.ImportVerdict;
import org.appdevforall.k2go.storage.StorageProbe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImportReviewViewModel extends ViewModel {

    private final Context appContext;
    private final Uri treeUri;
    private final ImportManifestReader reader;
    private final ImportSource source;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<ImportReviewUiState> state =
            new MutableLiveData<>(ImportReviewUiState.loading());
    private final MutableLiveData<ImportSelectionSummary> selection =
            new MutableLiveData<>(new ImportSelectionSummary(0, 0L, null, true));

    /** The validated items, in display order (grouped by app). Set on the loader thread. */
    private volatile List<ImportReviewItem> items = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();
    private volatile Long freeBytes;

    public ImportReviewViewModel(Context appContext, Uri treeUri,
                                 ImportManifestReader reader, ImportSource source) {
        this.appContext = appContext.getApplicationContext();
        this.treeUri = treeUri;
        this.reader = reader;
        this.source = source;
    }

    public LiveData<ImportReviewUiState> state() {
        return state;
    }

    public LiveData<ImportSelectionSummary> selection() {
        return selection;
    }

    public Uri treeUri() {
        return treeUri;
    }

    /** Read the bundle and validate it against the box, off the main thread. */
    public void load() {
        state.postValue(ImportReviewUiState.loading());
        executor.execute(() -> {
            try {
                SafImportCarrier carrier = new SafImportCarrier(appContext, treeUri);
                ImportManifest manifest = reader.read(carrier);
                List<ImportEntry> entries = manifest.entries();
                List<ImportVerdict> verdicts = source.validate(entries);

                List<ImportReviewItem> built = new ArrayList<>();
                selected.clear();
                for (int i = 0; i < entries.size(); i++) {
                    ImportVerdict v = i < verdicts.size()
                            ? verdicts.get(i)
                            : new ImportVerdict(ImportVerdict.STATUS_NO_APP, null, null);
                    built.add(new ImportReviewItem(i, entries.get(i), v));
                    if (v.defaultSelected()) {
                        selected.add(i);
                    }
                }
                // Group by app for display (stable within a group by original order).
                built.sort(Comparator.comparing((ImportReviewItem it) -> safe(it.entry().app())));

                this.items = built;
                this.freeBytes = StorageProbe.freeBytes(appContext);
                state.postValue(ImportReviewUiState.ready(built, manifest));
                publishSelection();
            } catch (Exception e) {
                state.postValue(ImportReviewUiState.error(e.getMessage()));
            }
        });
    }

    boolean isSelected(int index) {
        return selected.contains(index);
    }

    void toggle(int index) {
        for (ImportReviewItem it : items) {
            if (it.index() == index && it.selectable()) {
                if (!selected.remove(index)) {
                    selected.add(index);
                }
                publishSelection();
                return;
            }
        }
    }

    /**
     * Tri-state of a group (by app) over its SELECTABLE items only (a same-version or no-app item can
     * never be picked, so it is not counted). Derived from the single selection source.
     */
    int groupState(String app) {
        if (app == null) {
            return GroupSelection.NONE;
        }
        int selectable = 0;
        int chosen = 0;
        for (ImportReviewItem it : items) {
            if (!it.selectable() || !app.equals(safe(it.entry().app()))) {
                continue;
            }
            selectable++;
            if (selected.contains(it.index())) {
                chosen++;
            }
        }
        return GroupSelection.state(selectable, chosen);
    }

    /** Select or clear every selectable item in a group (by app) in one tap of its header control. */
    void toggleGroup(String app) {
        if (app == null) {
            return;
        }
        boolean selectAll = GroupSelection.shouldSelectAll(groupState(app));
        boolean changed = false;
        for (ImportReviewItem it : items) {
            if (!it.selectable() || !app.equals(safe(it.entry().app()))) {
                continue;
            }
            int idx = it.index();
            changed |= selectAll ? selected.add(idx) : selected.remove(idx);
        }
        if (changed) {
            publishSelection();
        }
    }

    /** The entries the user picked, in display order, for the import service. */
    List<ImportEntry> selectedEntries() {
        List<ImportEntry> out = new ArrayList<>();
        for (ImportReviewItem it : items) {
            if (selected.contains(it.index())) {
                out.add(it.entry());
            }
        }
        return out;
    }

    private void publishSelection() {
        long bytes = 0L;
        int count = 0;
        for (ImportReviewItem it : items) {
            if (selected.contains(it.index())) {
                count++;
                bytes += Math.max(0L, it.entry().bytes());
            }
        }
        selection.postValue(new ImportSelectionSummary(
                count, bytes, freeBytes, ImportPreflight.fits(bytes, freeBytes)));
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
