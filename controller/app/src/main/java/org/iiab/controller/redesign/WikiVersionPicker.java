package org.iiab.controller.redesign;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;
import org.json.JSONObject;

/**
 * Reusable Wikipedia-version picker. Renders a List (sortable by name/size) OR a Grouped-by-
 * coverage view of ONLY the variants a language actually offers, multi-select, into a
 * caller-provided container. The selection Set is shared and mutated in place so picks survive
 * the A/B flip. v1 downloads a single ZIM (WikiVariants.primary of the selection); the UI is
 * multi-capable for a later iteration.
 */
public class WikiVersionPicker {
    public interface OnChange { void changed(); }

    private final Context ctx;
    private final ViewGroup container;
    private final Set<String> selected;
    private final OnChange onChange;
    private JSONObject langData;

    private String mode;              // "list" | "grouped"
    private String sortKey = "size";  // "size" | "name"
    private int sortDir = 1;          // 1 asc, -1 desc

    public WikiVersionPicker(Context ctx, ViewGroup container, Set<String> selected, String mode, OnChange onChange) {
        this.ctx = ctx;
        this.container = container;
        this.selected = selected;
        this.mode = (mode == null) ? "list" : mode;
        this.onChange = onChange;
    }

    public void setLangData(JSONObject ld) { this.langData = ld; }
    public String getMode() { return mode; }
    public void setMode(String m) { this.mode = m; render(); }

    /** Pre-select the smallest available variant so the min-one rule is satisfied by default. */
    public void selectDefaultIfEmpty() {
        if (!selected.isEmpty() || langData == null) return;
        String best = null;
        double bs = Double.MAX_VALUE;
        for (String k : InstallationPlanner.availableVariants(langData)) {
            double s = WikiVariants.sizeGb(langData, k);
            if (s >= 0 && s < bs) { bs = s; best = k; }
        }
        if (best != null) selected.add(best);
    }

    private int dp(int d) { return Math.round(d * ctx.getResources().getDisplayMetrics().density); }

    public void render() {
        container.removeAllViews();
        if (container instanceof LinearLayout) ((LinearLayout) container).setOrientation(LinearLayout.VERTICAL);

        container.addView(toggleRow());

        List<String> a = InstallationPlanner.availableVariants(langData);
        if (a.isEmpty()) { container.addView(muted(ctx.getString(R.string.k2go_wiki_none))); return; }

        if ("grouped".equals(mode)) renderGrouped(a); else renderList(a);
    }

    private View toggleRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        row.addView(chip(ctx.getString(R.string.k2go_wiki_view_list), "list".equals(mode), new View.OnClickListener() {
            @Override public void onClick(View v) { setMode("list"); }
        }));
        View gap = new View(ctx);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(gap);
        row.addView(chip(ctx.getString(R.string.k2go_wiki_view_grouped), "grouped".equals(mode), new View.OnClickListener() {
            @Override public void onClick(View v) { setMode("grouped"); }
        }));
        return row;
    }

    private TextView chip(String text, boolean on, View.OnClickListener cl) {
        TextView t = new TextView(ctx);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(on ? R.drawable.k2go_primary_bg : R.drawable.k2go_card_bg);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setTextColor(ContextCompat.getColor(ctx, on ? R.color.k2go_on_teal : R.color.k2go_ink));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(cl);
        return t;
    }

    private void renderList(List<String> a) {
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(dp(10), 0, dp(10), dp(8));
        TextView hName = header(ctx.getString(R.string.k2go_wiki_col_version), "name");
        hName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(hName);
        head.addView(header(ctx.getString(R.string.k2go_wiki_col_size), "size"));
        container.addView(head);

        List<String> list = new ArrayList<>(a);
        Collections.sort(list, (x, y) -> {
            int c = "size".equals(sortKey)
                    ? Double.compare(WikiVariants.sizeGb(langData, x), WikiVariants.sizeGb(langData, y))
                    : WikiVariants.label(ctx, x).compareToIgnoreCase(WikiVariants.label(ctx, y));
            return c * sortDir;
        });
        for (String k : list) container.addView(variantRow(k, WikiVariants.label(ctx, k), 0));
    }

    private TextView header(String text, String key) {
        TextView t = new TextView(ctx);
        String arrow = sortKey.equals(key) ? (sortDir > 0 ? "  ▲" : "  ▼") : "";
        t.setText(text + arrow);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        t.setTextSize(12);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> {
            if (sortKey.equals(key)) sortDir = -sortDir; else { sortKey = key; sortDir = 1; }
            render();
        });
        return t;
    }

    private void renderGrouped(List<String> a) {
        String[] covs = {"all", "top1m", "top"};
        String[] dets = {"maxi", "nopic", "mini"};
        for (String cov : covs) {
            boolean any = false;
            for (String det : dets) if (a.contains(cov + "_" + det)) { any = true; break; }
            if (!any) continue;

            LinearLayout hd = new LinearLayout(ctx);
            hd.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = dp(10);
            hlp.bottomMargin = dp(6);
            hd.setLayoutParams(hlp);
            TextView title = new TextView(ctx);
            title.setText(WikiVariants.coverageName(ctx, cov));
            title.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
            title.setTextSize(14);
            TextView desc = new TextView(ctx);
            desc.setText(WikiVariants.coverageDesc(ctx, cov));
            desc.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
            desc.setTextSize(12);
            hd.addView(title);
            hd.addView(desc);
            container.addView(hd);

            for (String det : dets) {
                String k = cov + "_" + det;
                if (a.contains(k)) container.addView(variantRow(k, WikiVariants.detailName(ctx, det), dp(10)));
            }
        }
    }

    private View variantRow(final String key, String labelText, int indentPx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = indentPx;
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setClickable(true);
        row.setFocusable(true);

        final CheckBox cb = new CheckBox(ctx);
        cb.setChecked(selected.contains(key));
        cb.setClickable(false);
        cb.setFocusable(false);
        row.addView(cb);

        TextView name = new TextView(ctx);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(8);
        name.setLayoutParams(nlp);
        name.setText(labelText);
        name.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
        name.setTextSize(14);
        row.addView(name);

        TextView size = new TextView(ctx);
        size.setText(WikiVariants.gb(WikiVariants.sizeGb(langData, key)));
        size.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        size.setTextSize(13);
        row.addView(size);

        row.setOnClickListener(v -> {
            if (selected.contains(key)) selected.remove(key); else selected.add(key);
            cb.setChecked(selected.contains(key));
            if (onChange != null) onChange.changed();
        });
        return row;
    }

    private TextView muted(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        t.setTextSize(13);
        t.setPadding(dp(2), dp(6), dp(2), dp(6));
        return t;
    }
}
