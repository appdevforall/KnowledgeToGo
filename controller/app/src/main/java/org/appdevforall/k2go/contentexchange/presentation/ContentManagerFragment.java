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

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.checkbox.MaterialCheckBox;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.ContentGroup;
import org.appdevforall.k2go.contentexchange.domain.ContentItem;
import org.appdevforall.k2go.contentexchange.domain.ManagedContent;
import org.appdevforall.k2go.contentexchange.domain.StorageStatus;
import org.appdevforall.k2go.storage.StorageProbe;
import org.appdevforall.k2go.util.ByteFormatter;

/**
 * Reads the box content through {@link ContentManagerViewModel} and renders it with
 * the redesign's programmatic card pattern (no RecyclerView, like the module hub):
 * a storage summary, then one section per app with its items and sizes, each item a
 * selectable row. Selection lives in the ViewModel; the bottom bar reflects it.
 */
public class ContentManagerFragment extends Fragment {

    private ContentManagerViewModel viewModel;

    private LinearLayout storageBox;
    private LinearLayout cards;
    private TextView status;
    private View actionBar;
    private TextView selectionLabel;

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_manager, container, false);
        storageBox = root.findViewById(R.id.k2go_cx_storage);
        cards = root.findViewById(R.id.k2go_cx_cards);
        status = root.findViewById(R.id.k2go_cx_status);
        actionBar = root.findViewById(R.id.k2go_cx_actionbar);
        selectionLabel = root.findViewById(R.id.k2go_cx_selection_label);

        TextView back = root.findViewById(R.id.k2go_cx_back);
        back.setText(getString(R.string.k2go_back_link));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        viewModel = new ViewModelProvider(this, new ContentManagerViewModelFactory())
                .get(ContentManagerViewModel.class);
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
        storageBox.removeAllViews();
        cards.removeAllViews();
        switch (st.status) {
            case LOADING:
                showStatus(getString(R.string.k2go_cx_loading));
                return;
            case ERROR:
                showStatus(getString(R.string.k2go_cx_error));
                return;
            default:
                break;
        }
        status.setVisibility(View.GONE);
        renderStorage(st.storage, st.content);
        if (st.content.isEmpty()) {
            showStatus(getString(R.string.k2go_cx_empty));
            return;
        }
        for (ContentGroup group : st.content.groups()) {
            cards.addView(groupHeader(group));
            if (group.isEmpty()) {
                cards.addView(emptyGroupNote());
            } else {
                for (ContentItem item : group.items()) {
                    cards.addView(itemRow(item));
                }
            }
        }
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    /**
     * Used-by-content (from the core) + free disk. On K2Go the app owns the phone-disk truth, so the
     * free figure comes from the shared {@link StorageProbe} (StatFs), NOT the core's {@code /v1/storage}
     * box-volume number (a fiction on the phone). From the core we take only the content totals:
     * {@code usedByContentBytes} when {@code /v1/storage} is available (dedup-aware), else the item sum.
     */
    private void renderStorage(StorageStatus storage, ManagedContent content) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.k2go_card_bg);
        box.setPadding(px(16), px(14), px(16), px(14));

        long used = (storage != null && storage.available())
                ? storage.usedByContentBytes() : content.totalBytes();
        box.addView(summaryLine(getString(R.string.k2go_cx_used_fmt,
                ByteFormatter.humanBytes(used)), true));

        Long freeBytes = StorageProbe.freeBytes(requireContext());
        if (freeBytes != null) {
            box.addView(summaryLine(getString(R.string.k2go_cx_free_fmt,
                    ByteFormatter.humanBytes(freeBytes)), false));
        } else {
            box.addView(summaryLine(getString(R.string.k2go_cx_free_unavailable), false));
        }
        storageBox.addView(box);
    }

    private TextView summaryLine(String text, boolean strong) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextAppearance(strong
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        tv.setTextColor(ContextCompat.getColor(requireContext(),
                strong ? R.color.k2go_ink : R.color.k2go_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!strong) {
            lp.topMargin = px(2);
        }
        tv.setLayoutParams(lp);
        return tv;
    }

    /** App section header: display name + "{n} items : {size}". */
    private View groupHeader(ContentGroup group) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(16);
        lp.bottomMargin = px(8);
        row.setLayoutParams(lp);

        TextView name = new TextView(requireContext());
        name.setText(appDisplayName(group.app()));
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView meta = new TextView(requireContext());
        meta.setText(getString(R.string.k2go_cx_group_meta_fmt,
                group.items().size(), ByteFormatter.humanBytes(group.totalBytes())));
        meta.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        meta.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        row.addView(meta);
        return row;
    }

    private View emptyGroupNote() {
        TextView tv = new TextView(requireContext());
        tv.setText(getString(R.string.k2go_cx_group_empty));
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** A selectable content row: leading checkbox + title + size. */
    private View itemRow(final ContentItem item) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        final String id = item.selectionId();
        final MaterialCheckBox cb = new MaterialCheckBox(requireContext());
        cb.setChecked(viewModel.isSelected(id));
        cb.setOnClickListener(v -> viewModel.toggle(id));
        // Fixed-width leading slot so every row shares one indentation (K2GO-426 convention).
        FrameLayout slot = new FrameLayout(requireContext());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        slot.addView(cb, clp);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(40), LinearLayout.LayoutParams.WRAP_CONTENT);
        slotLp.rightMargin = px(4);
        row.addView(slot, slotLp);
        // Tapping the row toggles the checkbox too (larger, easier target).
        row.setClickable(true);
        row.setOnClickListener(v -> cb.performClick());

        TextView title = new TextView(requireContext());
        title.setText(item.title().isEmpty() ? item.localRef() : item.title());
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView size = new TextView(requireContext());
        size.setText(ByteFormatter.humanBytes(item.bytes()));
        size.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        size.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        LinearLayout.LayoutParams szLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        szLp.leftMargin = px(8);
        row.addView(size, szLp);
        return row;
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
