/*
 * ============================================================================
 * Name        : ContentManagerFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. The content manager (gestor): lists managed content
 *               grouped by app, each item with its size, plus total used and the
 *               free disk, with per-item multi-select. SLICE 1 (foundation): the
 *               bottom action bar (Export / Share / Delete) is present but
 *               disabled; those actions are later slices.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.ContentGroup;
import org.appdevforall.k2go.contentexchange.domain.ContentItem;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_manager, container, false);
        list = root.findViewById(R.id.k2go_cx_list);
        status = root.findViewById(R.id.k2go_cx_status);
        actionBar = root.findViewById(R.id.k2go_cx_actionbar);
        selectionLabel = root.findViewById(R.id.k2go_cx_selection_label);

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
