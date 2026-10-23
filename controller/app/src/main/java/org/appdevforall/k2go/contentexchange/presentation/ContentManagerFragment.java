/*
 * ============================================================================
 * Name        : ContentManagerFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. The content manager (gestor): lists managed content
 *               grouped by app, each item with its size, plus total used and the
 *               free disk, with per-item multi-select. The EXPORT action is wired
 *               (pick a drive via SAF, then write the open-format bundle through the
 *               foreground service); Share / Delete are later slices and stay disabled.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.ContentGroup;
import org.appdevforall.k2go.contentexchange.domain.ContentItem;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.contentexchange.domain.ManagedContent;
import org.appdevforall.k2go.contentexchange.domain.StorageStatus;
import org.appdevforall.k2go.storage.StorageProbe;
import org.appdevforall.k2go.util.ByteFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the box content through {@link ContentManagerViewModel} and renders it in a
 * {@link RecyclerView} (recycled item rows, so a large library does not inflate one
 * view per item). The storage summary and the per-app group headers are row types in
 * the same list. Selection lives in the ViewModel; the bottom bar reflects it.
 */
public class ContentManagerFragment extends Fragment {

    private ContentManagerViewModel viewModel;
    private ContentManagerAdapter adapter;

    private RecyclerView list;
    private TextView status;
    private View actionBar;
    private TextView selectionLabel;
    private Button exportButton;

    /** The folder picker; its result carries the drive/folder the user chose for the export. */
    private ActivityResultLauncher<Uri> openTree;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_manager, container, false);
        list = root.findViewById(R.id.k2go_cx_list);
        status = root.findViewById(R.id.k2go_cx_status);
        actionBar = root.findViewById(R.id.k2go_cx_actionbar);
        selectionLabel = root.findViewById(R.id.k2go_cx_selection_label);
        exportButton = root.findViewById(R.id.k2go_cx_export);

        openTree = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onTreePicked);
        exportButton.setEnabled(true);
        exportButton.setOnClickListener(v -> onExportClicked());

        TextView back = root.findViewById(R.id.k2go_cx_back);
        back.setText(getString(R.string.k2go_back_link));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        viewModel = new ViewModelProvider(this, new ContentManagerViewModelFactory())
                .get(ContentManagerViewModel.class);

        adapter = new ContentManagerAdapter(new ContentManagerAdapter.SelectionController() {
            @Override
            public boolean isSelected(String selectionId) {
                return viewModel.isSelected(selectionId);
            }

            @Override
            public void toggle(String selectionId) {
                viewModel.toggle(selectionId);
            }
        });
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.selection().observe(getViewLifecycleOwner(), this::renderSelection);

        if (s == null) {
            viewModel.load();
        }
        return root;
    }

    private void render(ContentManagerUiState st) {
        if (st == null) {
            return;
        }
        switch (st.status) {
            case LOADING:
                list.setVisibility(View.GONE);
                showStatus(getString(R.string.k2go_cx_loading));
                return;
            case ERROR:
                list.setVisibility(View.GONE);
                showStatus(getString(R.string.k2go_cx_error));
                return;
            default:
                break;
        }
        status.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        adapter.submit(buildRows(st.content, st.storage));
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    /** Flattens the grouped content into the adapter's row list (header, groups, items, empty). */
    private List<ContentManagerAdapter.Row> buildRows(ManagedContent content, StorageStatus storage) {
        List<ContentManagerAdapter.Row> rows = new ArrayList<>();
        rows.add(new ContentManagerAdapter.HeaderRow(
                getString(R.string.k2go_cx_title),
                getString(R.string.k2go_cx_subtitle),
                usedLine(content, storage),
                freeLine()));
        if (content.isEmpty()) {
            rows.add(new ContentManagerAdapter.EmptyRow(getString(R.string.k2go_cx_empty)));
            return rows;
        }
        for (ContentGroup group : content.groups()) {
            rows.add(new ContentManagerAdapter.GroupRow(
                    appDisplayName(group.app()),
                    getString(R.string.k2go_cx_group_meta_fmt,
                            group.items().size(), ByteFormatter.humanBytes(group.totalBytes()))));
            if (group.isEmpty()) {
                rows.add(new ContentManagerAdapter.EmptyRow(getString(R.string.k2go_cx_group_empty)));
            } else {
                for (ContentItem item : group.items()) {
                    rows.add(new ContentManagerAdapter.ItemRow(item));
                }
            }
        }
        return rows;
    }

    /**
     * "Used by content" from the core: {@code usedByContentBytes} when {@code /v1/storage} is
     * available (dedup-aware), else the item sum from {@code /v1/content}.
     */
    private String usedLine(ManagedContent content, StorageStatus storage) {
        long used = (storage != null && storage.available())
                ? storage.usedByContentBytes() : content.totalBytes();
        return getString(R.string.k2go_cx_used_fmt, ByteFormatter.humanBytes(used));
    }

    /**
     * Free disk from the shared {@link StorageProbe} (StatFs): on K2Go the app owns the phone-disk
     * truth, not the core's {@code /v1/storage} box-volume figure (a fiction on the phone).
     */
    private String freeLine() {
        Long freeBytes = StorageProbe.freeBytes(requireContext());
        return freeBytes != null
                ? getString(R.string.k2go_cx_free_fmt, ByteFormatter.humanBytes(freeBytes))
                : getString(R.string.k2go_cx_free_unavailable);
    }

    /** Updates the bottom action bar from the selection. Buttons stay disabled (slice 1). */
    private void renderSelection(SelectionSummary sel) {
        if (sel == null || sel.isEmpty()) {
            actionBar.setVisibility(View.GONE);
            return;
        }
        selectionLabel.setText(getString(R.string.k2go_cx_selected_fmt,
                sel.count, ByteFormatter.humanBytes(sel.bytes)));
        actionBar.setVisibility(View.VISIBLE);
    }

    /** The selection captured when Export was tapped, held across the folder picker round-trip. */
    private List<ContentItem> pendingExport;

    /** Export: if a run is already active, just show it; otherwise capture the selection and pick a drive. */
    private void onExportClicked() {
        if (ContentExportService.session().hasSession()) {
            showProgress();
            return;
        }
        List<ContentItem> selected = viewModel.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        pendingExport = selected;
        // SAF folder picker: no storage permission is requested; the persisted grant is the authority.
        openTree.launch(null);
    }

    /** The folder picker returned. Null means the user cancelled. */
    private void onTreePicked(@Nullable Uri treeUri) {
        if (treeUri == null || pendingExport == null || pendingExport.isEmpty()) {
            return;
        }
        // Persist read+write so the foreground service keeps access after this screen is gone.
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        android.content.ContentResolver resolver = requireContext().getContentResolver();
        try {
            resolver.takePersistableUriPermission(treeUri, flags);
        } catch (SecurityException e) {
            // The grant could not be persisted; proceed with the transient permission for this session.
        }
        // Safety net against unbounded growth: the run releases its own grant when it finishes, but an
        // export abandoned before "Finish" (e.g. the process is killed) would leave a stale grant. Export
        // is the app's only persisted-grant user, so drop every persisted tree except the one just taken.
        for (android.content.UriPermission p : resolver.getPersistedUriPermissions()) {
            if (!p.getUri().equals(treeUri)) {
                try {
                    resolver.releasePersistableUriPermission(p.getUri(), flags);
                } catch (Exception ignored) {
                    // best-effort prune
                }
            }
        }
        confirmAndStart(treeUri, pendingExport);
    }

    private void confirmAndStart(Uri treeUri, List<ContentItem> selected) {
        long total = 0L;
        for (ContentItem item : selected) {
            total += Math.max(0L, item.bytes());
        }
        String message = getString(R.string.k2go_cx_export_confirm_fmt,
                selected.size(), ByteFormatter.humanBytes(total));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.k2go_cx_action_export)
                .setMessage(message)
                .setNegativeButton(R.string.k2go_dash_cancel, null)
                .setPositiveButton(R.string.k2go_cx_action_export, (d, w) -> startExport(treeUri, selected))
                .show();
    }

    private void startExport(Uri treeUri, List<ContentItem> selected) {
        ItemRef[] refs = new ItemRef[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            refs[i] = new ItemRef(selected.get(i).app(), selected.get(i).localRef());
        }
        ContentExportService.start(requireContext(), treeUri, refs);
        viewModel.clearSelection();
        showProgress();
    }

    private void showProgress() {
        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new ExportProgressFragment())
                .addToBackStack(null)
                .commit();
    }

    /** Friendly app label; falls back to the raw adapter key for an unknown app. */
    private String appDisplayName(String app) {
        if (app == null) {
            return "";
        }
        switch (app) {
            case "kiwix":
                return getString(R.string.k2go_cx_app_kiwix);
            case "kolibri":
                return getString(R.string.k2go_cx_app_kolibri);
            case "calibre":
                return getString(R.string.k2go_cx_app_calibre);
            default:
                return app;
        }
    }
}
