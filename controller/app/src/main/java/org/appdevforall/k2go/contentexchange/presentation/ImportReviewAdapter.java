package org.appdevforall.k2go.contentexchange.presentation;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.GroupSelection;

import java.util.ArrayList;
import java.util.List;

/**
 * Recycling adapter for the import review screen. A flat row list with four view types: one header
 * block (source + a note when the bundle had no manifest), one header per app group, one row per
 * bundle item (checkbox + title + size + a state badge), and a note row (unrecognized files). Only the
 * item rows can be numerous, so recycling keeps a large bundle from inflating one view per item.
 *
 * <p>Selection lives in {@link ImportReviewViewModel}; the adapter reads and toggles it through
 * {@link SelectionController} by item index, so a recycled row always reflects the current selection.
 * A row the box cannot import (same version, older, no app) is shown disabled.
 */
final class ImportReviewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface SelectionController {
        boolean isSelected(int index);

        void toggle(int index);

        /** Tri-state of a group (by app): {@link GroupSelection#NONE}/{@code SOME}/{@code ALL}. */
        int groupState(String app);

        /** Select or clear every selectable item in a group (by app). */
        void toggleGroup(String app);
    }

    static final int TYPE_HEADER = 0;
    static final int TYPE_GROUP = 1;
    static final int TYPE_ITEM = 2;
    static final int TYPE_NOTE = 3;

    private final List<Row> rows = new ArrayList<>();
    private final SelectionController selection;

    ImportReviewAdapter(SelectionController selection) {
        this.selection = selection;
    }

    void submit(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    /** Rebind visible rows so group headers and item checkboxes reflect a changed selection. */
    void refreshSelectionStates() {
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();
        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderVH(ctx);
            case TYPE_GROUP:
                return new GroupVH(ctx, selection);
            case TYPE_NOTE:
                return new NoteVH(ctx);
            default:
                return new ItemVH(ctx, selection);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        switch (row.type) {
            case TYPE_HEADER:
                ((HeaderVH) holder).bind((HeaderRow) row);
                break;
            case TYPE_GROUP:
                ((GroupVH) holder).bind((GroupRow) row);
                break;
            case TYPE_NOTE:
                ((NoteVH) holder).bind((NoteRow) row);
                break;
            default:
                ((ItemVH) holder).bind((ItemRow) row);
                break;
        }
    }

    // --- Row model (strings resolved by the Fragment) ---

    abstract static class Row {
        final int type;

        Row(int type) {
            this.type = type;
        }
    }

    static final class HeaderRow extends Row {
        final String title;
        final String subtitle;

        HeaderRow(String title, String subtitle) {
            super(TYPE_HEADER);
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    static final class GroupRow extends Row {
        final String app;
        final String name;
        final String meta;

        GroupRow(String app, String name, String meta) {
            super(TYPE_GROUP);
            this.app = app;
            this.name = name;
            this.meta = meta;
        }
    }

    static final class ItemRow extends Row {
        final int index;
        final String title;
        final String size;
        final String state;
        final boolean selectable;

        ItemRow(int index, String title, String size, String state, boolean selectable) {
            super(TYPE_ITEM);
            this.index = index;
            this.title = title;
            this.size = size;
            this.state = state;
            this.selectable = selectable;
        }
    }

    static final class NoteRow extends Row {
        final String text;

        NoteRow(String text) {
            super(TYPE_NOTE);
            this.text = text;
        }
    }

    // --- View holders (built programmatically, mirroring the manager adapter) ---

    private static int px(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    private static RecyclerView.LayoutParams fullWidth(int topMarginPx, int bottomMarginPx) {
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginPx;
        lp.bottomMargin = bottomMarginPx;
        return lp;
    }

    private static TextView text(Context ctx, int appearance, int colorRes) {
        TextView tv = new TextView(ctx);
        tv.setTextAppearance(appearance);
        tv.setTextColor(ContextCompat.getColor(ctx, colorRes));
        return tv;
    }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView subtitle;

        HeaderVH(Context ctx) {
            super(new LinearLayout(ctx));
            LinearLayout root = (LinearLayout) itemView;
            root.setOrientation(LinearLayout.VERTICAL);
            root.setLayoutParams(fullWidth(px(ctx, 16), px(ctx, 4)));

            title = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium, R.color.k2go_ink);
            root.addView(title);

            subtitle = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.k2go_muted);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = px(ctx, 4);
            root.addView(subtitle, subLp);
        }

        void bind(HeaderRow row) {
            title.setText(row.title);
            if (row.subtitle == null || row.subtitle.isEmpty()) {
                subtitle.setVisibility(android.view.View.GONE);
            } else {
                subtitle.setVisibility(android.view.View.VISIBLE);
                subtitle.setText(row.subtitle);
            }
        }
    }

    static final class GroupVH extends RecyclerView.ViewHolder {
        private final MaterialCheckBox checkBox;
        private final TextView name;
        private final TextView meta;
        private final SelectionController selection;
        private String boundApp;

        GroupVH(Context ctx, SelectionController selection) {
            super(new LinearLayout(ctx));
            this.selection = selection;
            LinearLayout root = (LinearLayout) itemView;
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setLayoutParams(fullWidth(px(ctx, 16), px(ctx, 8)));

            checkBox = new MaterialCheckBox(ctx);
            checkBox.setContentDescription(ctx.getString(R.string.k2go_cx_select_group));
            // Same 40dp leading slot as the item rows, so the group box sits above the item boxes.
            FrameLayout slot = new FrameLayout(ctx);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
            clp.gravity = Gravity.CENTER;
            slot.addView(checkBox, clp);
            LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(ctx, 40), -2);
            slotLp.rightMargin = px(ctx, 4);
            root.addView(slot, slotLp);

            name = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink);
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
            root.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            meta = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted);
            root.addView(meta);

            // User taps only: setCheckedState() during bind does not fire this (no OnCheckedChange
            // listener), so a recycled header never toggles the model by itself.
            checkBox.setOnClickListener(v -> {
                if (boundApp != null) {
                    this.selection.toggleGroup(boundApp);
                }
            });
        }

        void bind(GroupRow row) {
            boundApp = row.app;
            name.setText(row.name);
            meta.setText(row.meta);
            int state = selection.groupState(row.app);
            checkBox.setCheckedState(state == GroupSelection.ALL
                    ? MaterialCheckBox.STATE_CHECKED
                    : state == GroupSelection.SOME
                    ? MaterialCheckBox.STATE_INDETERMINATE
                    : MaterialCheckBox.STATE_UNCHECKED);
        }
    }

    static final class ItemVH extends RecyclerView.ViewHolder {
        private final MaterialCheckBox checkBox;
        private final TextView title;
        private final TextView size;
        private final TextView state;
        private final SelectionController selection;
        private int boundIndex = -1;

        ItemVH(Context ctx, SelectionController selection) {
            super(new LinearLayout(ctx));
            this.selection = selection;
            LinearLayout row = (LinearLayout) itemView;
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.k2go_card_bg);
            row.setPadding(px(ctx, 16), px(ctx, 14), px(ctx, 16), px(ctx, 14));
            row.setLayoutParams(fullWidth(0, px(ctx, 12)));

            checkBox = new MaterialCheckBox(ctx);
            FrameLayout slot = new FrameLayout(ctx);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
            clp.gravity = Gravity.CENTER;
            slot.addView(checkBox, clp);
            LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(ctx, 40), -2);
            slotLp.rightMargin = px(ctx, 4);
            row.addView(slot, slotLp);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

            title = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink);
            texts.addView(title);
            state = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium, R.color.k2go_teal);
            texts.addView(state);

            size = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted);
            LinearLayout.LayoutParams szLp = new LinearLayout.LayoutParams(-2, -2);
            szLp.leftMargin = px(ctx, 8);
            row.addView(size, szLp);

            checkBox.setOnClickListener(v -> {
                if (boundIndex >= 0) {
                    this.selection.toggle(boundIndex);
                }
            });
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (checkBox.isEnabled()) {
                    checkBox.performClick();
                }
            });
        }

        void bind(ItemRow row) {
            boundIndex = row.index;
            title.setText(row.title);
            size.setText(row.size);
            state.setText(row.state);
            int stateColor = row.selectable ? R.color.k2go_teal : R.color.k2go_muted;
            state.setTextColor(ContextCompat.getColor(state.getContext(), stateColor));
            checkBox.setEnabled(row.selectable);
            itemView.setEnabled(row.selectable);
            itemView.setAlpha(row.selectable ? 1f : 0.6f);
            checkBox.setChecked(row.selectable && selection.isSelected(row.index));
        }
    }

    static final class NoteVH extends RecyclerView.ViewHolder {
        private final TextView tv;

        NoteVH(Context ctx) {
            super(text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted));
            tv = (TextView) itemView;
            tv.setLayoutParams(fullWidth(0, px(ctx, 12)));
        }

        void bind(NoteRow row) {
            tv.setText(row.text);
        }
    }
}
