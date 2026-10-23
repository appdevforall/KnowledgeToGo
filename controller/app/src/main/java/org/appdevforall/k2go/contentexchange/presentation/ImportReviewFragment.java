/*
 * ============================================================================
 * Name        : ImportReviewFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. The import review screen: reads a bundle from the picked drive, validates it
 *               against this box, and lists the items grouped by app with a state badge and a checkbox.
 *               The bottom bar shows the selected total against the device free space (the phone-disk
 *               preflight) and starts the import foreground service when the user confirms.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.util.ByteFormatter;

import java.util.ArrayList;
import java.util.List;

public class ImportReviewFragment extends Fragment {

    private static final String ARG_TREE_URI = "treeUri";

    /** Create the review for a drive tree the manager already picked and persisted a grant for. */
    public static ImportReviewFragment newInstance(Uri treeUri) {
        ImportReviewFragment f = new ImportReviewFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TREE_URI, treeUri.toString());
        f.setArguments(b);
        return f;
    }

    private ImportReviewViewModel viewModel;
    private ImportReviewAdapter adapter;

    private RecyclerView list;
    private TextView status;
    private View actionBar;
    private TextView selectionLabel;
    private TextView freeLabel;
    private TextView warning;
    private Button importButton;

    private boolean currentFits = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_import_review, container, false);
        list = root.findViewById(R.id.k2go_cxr_list);
        status = root.findViewById(R.id.k2go_cxr_status);
        actionBar = root.findViewById(R.id.k2go_cxr_actionbar);
        selectionLabel = root.findViewById(R.id.k2go_cxr_selection_label);
        freeLabel = root.findViewById(R.id.k2go_cxr_free_label);
        warning = root.findViewById(R.id.k2go_cxr_warning);
        importButton = root.findViewById(R.id.k2go_cxr_import);

        TextView back = root.findViewById(R.id.k2go_cxr_back);
        back.setText(getString(R.string.k2go_back_link));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        importButton.setOnClickListener(v -> onImportClicked());

        Uri treeUri = Uri.parse(requireArguments().getString(ARG_TREE_URI));
        viewModel = new ViewModelProvider(this, new ImportReviewViewModelFactory(requireContext(), treeUri))
                .get(ImportReviewViewModel.class);

        adapter = new ImportReviewAdapter(new ImportReviewAdapter.SelectionController() {
            @Override
            public boolean isSelected(int index) {
                return viewModel.isSelected(index);
            }

            @Override
            public void toggle(int index) {
                viewModel.toggle(index);
            }

            @Override
            public int groupState(String app) {
                return viewModel.groupState(app);
            }

            @Override
            public void toggleGroup(String app) {
                viewModel.toggleGroup(app);
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

    private void render(ImportReviewUiState st) {
        if (st == null) {
            return;
        }
        switch (st.status) {
            case LOADING:
                list.setVisibility(View.GONE);
                showStatus(getString(R.string.k2go_cx_import_reading));
                return;
            case ERROR:
                list.setVisibility(View.GONE);
                showStatus(getString(R.string.k2go_cx_import_read_error));
                return;
            default:
                break;
        }
        status.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        adapter.submit(buildRows(st));
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    /** Flatten the validated items into grouped rows (header, group headers, item rows, notes). */
    private List<ImportReviewAdapter.Row> buildRows(ImportReviewUiState st) {
        List<ImportReviewAdapter.Row> rows = new ArrayList<>();
        String subtitle = st.hadManifest
                ? getString(R.string.k2go_cx_import_subtitle)
                : getString(R.string.k2go_cx_import_subtitle_scanned);
        rows.add(new ImportReviewAdapter.HeaderRow(getString(R.string.k2go_cx_import_title), subtitle));

        if (st.items.isEmpty()) {
            rows.add(new ImportReviewAdapter.NoteRow(getString(R.string.k2go_cx_import_nothing)));
        } else {
            String currentApp = null;
            int groupCount = 0;
            long groupBytes = 0L;
            int groupHeaderAt = -1;
            for (ImportReviewItem item : st.items) {
                String app = item.entry().app();
                if (currentApp == null || !currentApp.equals(app)) {
                    currentApp = app;
                    groupHeaderAt = rows.size();
                    rows.add(new ImportReviewAdapter.GroupRow(app, appDisplayName(app), ""));
                    groupCount = 0;
                    groupBytes = 0L;
                }
                rows.add(itemRow(item));
                groupCount++;
                groupBytes += Math.max(0L, item.entry().bytes());
                // Rewrite the group header meta as the running totals (header was added first).
                rows.set(groupHeaderAt, new ImportReviewAdapter.GroupRow(app, appDisplayName(app),
                        getString(R.string.k2go_cx_group_meta_fmt, groupCount,
                                ByteFormatter.humanBytes(groupBytes))));
            }
        }

        if (st.unrecognized != null && !st.unrecognized.isEmpty()) {
            rows.add(new ImportReviewAdapter.NoteRow(getString(R.string.k2go_cx_import_unrecognized_fmt,
                    android.text.TextUtils.join(", ", st.unrecognized))));
        }
        return rows;
    }

    private ImportReviewAdapter.ItemRow itemRow(ImportReviewItem item) {
        ImportEntry e = item.entry();
        String title = e.title() != null && !e.title().isEmpty() ? e.title() : e.localRef();
        String size = ByteFormatter.humanBytes(e.bytes());
        String state = stateLabel(item);
        return new ImportReviewAdapter.ItemRow(item.index(), title, size, state, item.selectable());
    }

    private String stateLabel(ImportReviewItem item) {
        String base;
        if (!item.verdict().hasApp()) {
            base = getString(R.string.k2go_cx_import_state_no_app);
        } else if (item.verdict().isNew()) {
            base = getString(R.string.k2go_cx_import_state_new);
        } else if (item.verdict().isUpdate()) {
            base = getString(R.string.k2go_cx_import_state_update);
        } else if (item.verdict().isSame()) {
            base = getString(R.string.k2go_cx_import_state_same);
        } else if (item.verdict().isOlderIncoming()) {
            base = getString(R.string.k2go_cx_import_state_older);
        } else {
            base = getString(R.string.k2go_cx_import_state_no_app);
        }
        // A soft (Calibre) verdict is advisory, not a hard fact: mark it so the user knows.
        if (item.verdict().hasApp() && item.verdict().isAdvisory()) {
            return getString(R.string.k2go_cx_import_state_advisory_fmt, base);
        }
        return base;
    }

    private void renderSelection(ImportSelectionSummary sel) {
        // Rebind rows so group headers (tri-state) and item checkboxes match the new selection.
        if (adapter != null) {
            adapter.refreshSelectionStates();
        }
        if (sel == null || sel.isEmpty()) {
            actionBar.setVisibility(View.GONE);
            currentFits = false;
            return;
        }
        actionBar.setVisibility(View.VISIBLE);
        selectionLabel.setText(getString(R.string.k2go_cx_selected_fmt,
                sel.count, ByteFormatter.humanBytes(sel.bytes)));
        freeLabel.setText(sel.freeBytes != null
                ? getString(R.string.k2go_cx_free_fmt, ByteFormatter.humanBytes(sel.freeBytes))
                : getString(R.string.k2go_cx_free_unavailable));
        currentFits = sel.fits;
        warning.setVisibility(sel.fits ? View.GONE : View.VISIBLE);
        if (!sel.fits) {
            warning.setText(getString(R.string.k2go_cx_import_wont_fit));
        }
        importButton.setEnabled(sel.fits);
    }

    private void onImportClicked() {
        if (!currentFits) {
            return;
        }
        List<ImportEntry> selected = viewModel.selectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        int n = selected.size();
        ItemRef[] refs = new ItemRef[n];
        String[] titles = new String[n];
        String[] entries = new String[n];
        String[] payloads = new String[n];
        long[] bytes = new long[n];
        for (int i = 0; i < n; i++) {
            ImportEntry e = selected.get(i);
            refs[i] = e.ref();
            titles[i] = e.title() != null && !e.title().isEmpty() ? e.title() : e.localRef();
            entries[i] = e.entryJson();
            payloads[i] = e.payloadName();
            bytes[i] = e.bytes();
        }
        ContentImportService.start(requireContext(), viewModel.treeUri(), refs, titles, entries, payloads, bytes);
        showProgress();
    }

    private void showProgress() {
        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new ImportProgressFragment())
                .addToBackStack(null)
                .commit();
    }

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
