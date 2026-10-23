package org.appdevforall.k2go.contentexchange.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.contentexchange.domain.ContentGroup;
import org.appdevforall.k2go.contentexchange.domain.ContentItem;
import org.appdevforall.k2go.contentexchange.domain.GetStorageStatusUseCase;
import org.appdevforall.k2go.contentexchange.domain.GroupSelection;
import org.appdevforall.k2go.contentexchange.domain.ListManagedContentUseCase;
import org.appdevforall.k2go.contentexchange.domain.ManagedContent;
import org.appdevforall.k2go.contentexchange.domain.StorageStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presentation-layer ViewModel for the content manager.
 *
 * <p>Loads the grouped content and the storage summary off the main thread (one
 * posts LOADING then SUCCESS/ERROR), and owns the transient multi-selection. The
 * selection is a plain {@link Set} of {@link ContentItem#selectionId()} values held
 * here (encapsulated ViewModel state, not persisted and not a static), so it
 * survives a config change but not a process death, which is correct for an
 * ephemeral pick.
 *
 * <p>Depends only on Domain use cases, never on the Data layer directly.
 */
public class ContentManagerViewModel extends ViewModel {

    private final ListManagedContentUseCase listContent;
    private final GetStorageStatusUseCase getStorage;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<ContentManagerUiState> state =
            new MutableLiveData<>(ContentManagerUiState.loading());
    private final MutableLiveData<SelectionSummary> selection =
            new MutableLiveData<>(SelectionSummary.empty());

    private final Set<String> selectedIds = new HashSet<>();
    /** selectionId -> item bytes, rebuilt on each successful load, for the selection total. */
    private final Map<String, Long> bytesById = new HashMap<>();
    /** The last loaded content, so the action slices can resolve the selected items. Volatile: set on
     *  the loader thread, read on the main thread after the SUCCESS state is observed. */
    private volatile ManagedContent lastContent;

    public ContentManagerViewModel(ListManagedContentUseCase listContent, GetStorageStatusUseCase getStorage) {
        this.listContent = listContent;
        this.getStorage = getStorage;
    }

    public LiveData<ContentManagerUiState> state() {
        return state;
    }

    public LiveData<SelectionSummary> selection() {
        return selection;
    }

    /** Loads content + storage. Content failure -> ERROR; storage failure only hides the free figure. */
    public void load() {
        state.postValue(ContentManagerUiState.loading());
        executor.execute(() -> {
            try {
                ManagedContent content = listContent.execute();
                StorageStatus storage = getStorage.execute();
                lastContent = content;
                indexBytes(content);
                state.postValue(ContentManagerUiState.success(content, storage));
            } catch (Exception e) {
                state.postValue(ContentManagerUiState.error(e.getMessage()));
            }
        });
    }

    public boolean isSelected(String selectionId) {
        return selectedIds.contains(selectionId);
    }

    /** The currently selected items (for an action slice: export / delete). Empty if nothing loaded. */
    public List<ContentItem> selectedItems() {
        List<ContentItem> out = new ArrayList<>();
        ManagedContent content = lastContent;
        if (content == null) {
            return out;
        }
        for (ContentGroup group : content.groups()) {
            for (ContentItem item : group.items()) {
                if (selectedIds.contains(item.selectionId())) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /** Toggles one item's selection and republishes the summary. */
    public void toggle(String selectionId) {
        if (!selectedIds.remove(selectionId)) {
            selectedIds.add(selectionId);
        }
        publishSelection();
    }

    public void clearSelection() {
        if (!selectedIds.isEmpty()) {
            selectedIds.clear();
            publishSelection();
        }
    }

    /**
     * Tri-state of a group (by app) for the header's "select all" control: NONE, SOME or ALL. Derived
     * from the single selection source, never stored separately.
     */
    public int groupState(String app) {
        ManagedContent content = lastContent;
        if (content == null || app == null) {
            return GroupSelection.NONE;
        }
        int selectable = 0;
        int selected = 0;
        for (ContentGroup group : content.groups()) {
            if (!app.equals(group.app())) {
                continue;
            }
            for (ContentItem item : group.items()) {
                selectable++;
                if (selectedIds.contains(item.selectionId())) {
                    selected++;
                }
            }
        }
        return GroupSelection.state(selectable, selected);
    }

    /** Select or clear every item in a group (by app) in one tap of its header control. */
    public void toggleGroup(String app) {
        ManagedContent content = lastContent;
        if (content == null || app == null) {
            return;
        }
        boolean selectAll = GroupSelection.shouldSelectAll(groupState(app));
        boolean changed = false;
        for (ContentGroup group : content.groups()) {
            if (!app.equals(group.app())) {
                continue;
            }
            for (ContentItem item : group.items()) {
                String id = item.selectionId();
                changed |= selectAll ? selectedIds.add(id) : selectedIds.remove(id);
            }
        }
        if (changed) {
            publishSelection();
        }
    }

    /** Rebuilds the id->bytes index and drops any selected id that no longer exists. */
    private void indexBytes(ManagedContent content) {
        bytesById.clear();
        for (ContentGroup group : content.groups()) {
            for (ContentItem item : group.items()) {
                bytesById.put(item.selectionId(), Math.max(0L, item.bytes()));
            }
        }
        selectedIds.retainAll(bytesById.keySet());
        publishSelection();
    }

    private void publishSelection() {
        long total = 0L;
        for (String id : selectedIds) {
            Long b = bytesById.get(id);
            if (b != null) {
                total += b;
            }
        }
        selection.postValue(new SelectionSummary(selectedIds.size(), total));
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
