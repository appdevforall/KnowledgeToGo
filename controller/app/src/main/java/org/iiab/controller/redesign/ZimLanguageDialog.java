/*
 * ============================================================================
 * Name        : ZimLanguageDialog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. Searchable language picker (type to filter) over the ZIM catalog's
 *               own languages — kiwix has hundreds of codes (incl. non-ISO Wikimedia ones), far
 *               beyond the app's supported UI languages, so this runs over the catalog set. Same
 *               "search + single-choice list" pattern as the wizard's language picker; reused by
 *               the ZIM landing and category screens as the standard language selector.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ZimLanguageDialog {
    private ZimLanguageDialog() {}

    public interface Display { String name(String code); }
    public interface OnPick { void pick(String code); }

    public static void show(Context ctx, String title, List<String> codes, Display disp,
                            String current, OnPick onPick) {
        show(ctx, title, codes, disp, current, onPick, null, null);
    }

    /** With an optional pinned row on top (e.g. "Follow system language"). */
    public static void show(Context ctx, String title, List<String> codes, Display disp,
                            String current, OnPick onPick, String pinnedLabel, Runnable onPinned) {
        final List<String> sorted = new ArrayList<>(codes);
        Collections.sort(sorted, (a, b) -> disp.name(a).compareToIgnoreCase(disp.name(b)));

        int pad = dp(ctx, 16);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(ctx, 8), pad, 0);

        EditText search = new EditText(ctx);
        search.setHint(R.string.k2go_lang_search_hint);
        search.setSingleLine(true);
        search.setBackgroundResource(R.drawable.k2go_card_bg);
        search.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        root.addView(search);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout listv = new LinearLayout(ctx);
        listv.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listv);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 360));
        svlp.topMargin = dp(ctx, 8);
        root.addView(sv, svlp);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            listv.removeAllViews();
            if (onPinned != null) {
                listv.addView(row(ctx, pinnedLabel, false, () -> { onPinned.run(); dialog.dismiss(); }));
            }
            for (String code : sorted) {
                String label = disp.name(code) + "  (" + code + ")";
                if (!term.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(term)) continue;
                listv.addView(row(ctx, label, code.equals(current), () -> { onPick.pick(code); dialog.dismiss(); }));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) { render[0].run(); }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        });
        render[0].run();
        dialog.show();
    }

    private static TextView row(Context ctx, String label, boolean sel, Runnable onClick) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(17);
        t.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 14));
        t.setTextColor(ContextCompat.getColor(ctx, sel ? R.color.k2go_teal : R.color.k2go_ink));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
