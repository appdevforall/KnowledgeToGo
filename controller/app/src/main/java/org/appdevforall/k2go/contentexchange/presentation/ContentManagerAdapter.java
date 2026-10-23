package org.appdevforall.k2go.contentexchange.presentation;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.ContentItem;
import org.appdevforall.k2go.contentexchange.domain.GroupSelection;
import org.appdevforall.k2go.util.ByteFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Recycling adapter for the content manager. A single flat row list with four view
 * types: one header block (title + subtitle + storage summary), one header per app
 * group, one row per content item, and an empty-state note. Only the item rows can
 * be numerous: recycling keeps a large library (hundreds of books / many ZIMs) from
 * inflating one view per item at once, which janks and can OOM weak 32-bit devices.
 *
 * <p>Selection state is NOT held here: it lives in {@link ContentManagerViewModel};
 * the adapter reads and toggles it through {@link SelectionController}, so a rebound
 * (recycled) row always reflects the current selection.
 */
public final class ContentManagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** The adapter's window into the ViewModel-owned selection. */
    public interface SelectionController {
        boolean isSelected(String selectionId);

        void toggle(String selectionId);

        /** Tri-state of a group (by app): {@link GroupSelection#NONE}/{@code SOME}/{@code ALL}. */
        int groupState(String app);

        /** Select or clear every item in a group (by app). */
        void toggleGroup(String app);
    }

    static final int TYPE_HEADER = 0;
    static final int TYPE_GROUP = 1;
    static final int TYPE_ITEM = 2;
    static final int TYPE_EMPTY = 3;

    private final List<Row> rows = new ArrayList<>();
    private final SelectionController selection;

    public ContentManagerAdapter(SelectionController selection) {
        this.selection = selection;
    }

    public void submit(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    /** Rebind visible rows so group headers and item checkboxes reflect a changed selection. */
    public void refreshSelectionStates() {
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
            case TYPE_EMPTY:
                return new EmptyVH(ctx);
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
            case TYPE_EMPTY:
                ((EmptyVH) holder).bind((EmptyRow) row);
                break;
            default:
                ((ItemVH) holder).bind((ItemRow) row);
                break;
        }
    }

    // --- Row model (all strings resolved by the Fragment, which owns Context/resources) ---

    public abstract static class Row {
        final int type;

        Row(int type) {
            this.type = type;
        }
    }

    public static final class HeaderRow extends Row {
        final String title;
        final String subtitle;
        final String usedLine;
        final String freeLine;

        public HeaderRow(String title, String subtitle, String usedLine, String freeLine) {
            super(TYPE_HEADER);
            this.title = title;
            this.subtitle = subtitle;
            this.usedLine = usedLine;
            this.freeLine = freeLine;
        }
    }

    public static final class GroupRow extends Row {
        final String app;
        final String name;
        final String meta;
        final boolean selectable;   // the group has at least one item that can be picked

        public GroupRow(String app, String name, String meta, boolean selectable) {
            super(TYPE_GROUP);
            this.app = app;
            this.name = name;
            this.meta = meta;
            this.selectable = selectable;
        }
    }

    public static final class ItemRow extends Row {
        final ContentItem item;

        public ItemRow(ContentItem item) {
            super(TYPE_ITEM);
            this.item = item;
        }
    }

    public static final class EmptyRow extends Row {
        final String text;

        public EmptyRow(String text) {
            super(TYPE_EMPTY);
            this.text = text;
        }
    }

    // --- View holders (views built programmatically, like the redesign's card rows) ---

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
        private final TextView used;
        private final TextView free;

        HeaderVH(Context ctx) {
            super(new LinearLayout(ctx));
            LinearLayout root = (LinearLayout) itemView;
            root.setOrientation(LinearLayout.VERTICAL);
            root.setLayoutParams(fullWidth(px(ctx, 16), px(ctx, 4)));

            title = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium, R.color.k2go_ink);
            root.addView(title);

            subtitle = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, R.color.k2go_muted);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = px(ctx, 4);
            root.addView(subtitle, subLp);

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.k2go_card_bg);
            card.setPadding(px(ctx, 16), px(ctx, 14), px(ctx, 16), px(ctx, 14));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.topMargin = px(ctx, 16);
            root.addView(card, cardLp);

            used = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink);
            card.addView(used);
            free = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.k2go_muted);
            LinearLayout.LayoutParams freeLp = new LinearLayout.LayoutParams(-1, -2);
            freeLp.topMargin = px(ctx, 2);
            card.addView(free, freeLp);
        }

        void bind(HeaderRow row) {
            title.setText(row.title);
            subtitle.setText(row.subtitle);
            used.setText(row.usedLine);
            free.setText(row.freeLine);
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
            // An empty group gets a disabled box, not a live control that does nothing when tapped.
            checkBox.setEnabled(row.selectable);
            int state = row.selectable ? selection.groupState(row.app) : GroupSelection.NONE;
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
        private final SelectionController selection;
        private String boundId;

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
            // Fixed-width leading slot so every row shares one indentation (K2GO-426 convention).
            FrameLayout slot = new FrameLayout(ctx);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
            clp.gravity = Gravity.CENTER;
            slot.addView(checkBox, clp);
            LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(ctx, 40), -2);
            slotLp.rightMargin = px(ctx, 4);
            row.addView(slot, slotLp);

            title = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            size = text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted);
            LinearLayout.LayoutParams szLp = new LinearLayout.LayoutParams(-2, -2);
            szLp.leftMargin = px(ctx, 8);
            row.addView(size, szLp);

            // User taps only: setChecked() during bind does not fire OnClickListener, so a recycled
            // row never toggles the model by itself. A row tap performs the checkbox click (bigger target).
            checkBox.setOnClickListener(v -> {
                if (boundId != null) {
                    this.selection.toggle(boundId);
                }
            });
            row.setClickable(true);
            row.setOnClickListener(v -> checkBox.performClick());
        }

        void bind(ItemRow row) {
            ContentItem item = row.item;
            boundId = item.selectionId();
            title.setText(item.title().isEmpty() ? item.localRef() : item.title());
            size.setText(ByteFormatter.humanBytes(item.bytes()));
            checkBox.setChecked(selection.isSelected(boundId));
        }
    }

    static final class EmptyVH extends RecyclerView.ViewHolder {
        private final TextView tv;

        EmptyVH(Context ctx) {
            super(text(ctx, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted));
            tv = (TextView) itemView;
            tv.setLayoutParams(fullWidth(0, px(ctx, 12)));
        }

        void bind(EmptyRow row) {
            tv.setText(row.text);
        }
    }
}
