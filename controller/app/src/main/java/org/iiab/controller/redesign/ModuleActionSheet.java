/*
 * ============================================================================
 * Name        : ModuleActionSheet.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4958. The single contextual surface for a Home module card — a Material 3
 *               modal bottom sheet with labeled rows. It does NOT install/schedule itself; it
 *               DELEGATES to Module management (ModuleWishlist + SetupLibraryActivity), the proven
 *               proot-safe flow. Long-press stays reserved for Help.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.iiab.controller.R;
import org.iiab.controller.config.BoxEndpoints;

public final class ModuleActionSheet {

    private ModuleActionSheet() {}

    public enum State { READY, NOT_INSTALLED, SCHEDULED }

    /**
     * Open the sheet for a Home experience card. {@code endpoint} is the card's server path
     * (e.g. "books"); it maps to a module via {@link ModuleCards#byEndpoint(String)}. Non-module
     * cards (e.g. "maps") should not call this. {@code onChanged} refreshes Home after a schedule
     * or cancel (the wishlist changed).
     */
    public static void show(Activity act, String endpoint, String title, int iconRes,
                            State state, Runnable onChanged) {
        final Context ctx = act;
        final ModuleCards.Card card = ModuleCards.byEndpoint(endpoint);
        final String key = card != null ? card.key() : null;
        final boolean sel = card != null && card.hasSelector;   // ADFA-4958: maps = module WITH a content selector

        final BottomSheetDialog dlg = new BottomSheetDialog(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(ContextCompat.getColor(ctx, R.color.k2go_surface));
        content.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 20));

        // Drag handle.
        View handle = new View(ctx);
        handle.setBackgroundColor(ContextCompat.getColor(ctx, R.color.k2go_hairline));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(ctx, 12);
        content.addView(handle, hlp);

        // Header: glyph + title + subtitle (project · state).
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView glyph = new ImageView(ctx);
        glyph.setImageResource(iconRes);
        glyph.setColorFilter(ContextCompat.getColor(ctx, R.color.k2go_teal));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28));
        glp.rightMargin = dp(ctx, 12);
        header.addView(glyph, glp);
        LinearLayout hcol = new LinearLayout(ctx);
        hcol.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
        hcol.addView(tv);
        TextView subtitle = new TextView(ctx);
        String project = (card != null) ? act.getString(card.subRes) : "";
        subtitle.setText(project.isEmpty() ? stateLabel(act, state) : project + "  ·  " + stateLabel(act, state));
        subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        subtitle.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        hcol.addView(subtitle);
        LinearLayout.LayoutParams hcolp = new LinearLayout.LayoutParams(0, -2, 1f);
        header.addView(hcol, hcolp);
        LinearLayout.LayoutParams headlp = new LinearLayout.LayoutParams(-1, -2);
        headlp.bottomMargin = dp(ctx, 8);
        content.addView(header, headlp);

        switch (state) {
            case READY:
                content.addView(row(ctx, R.drawable.ic_arrow_right, act.getString(R.string.k2go_sheet_open),
                        R.color.k2go_teal, false, v -> { dlg.dismiss(); openContent(act, endpoint); }));
                content.addView(row(ctx, R.drawable.ic_info_24, act.getString(R.string.k2go_sheet_about),
                        R.color.k2go_ink, true, v -> { dlg.dismiss(); openDetail(act, key); }));
                break;
            case SCHEDULED: {
                content.addView(row(ctx, R.drawable.ic_info_24, act.getString(R.string.k2go_sheet_about),
                        R.color.k2go_ink, true, v -> { dlg.dismiss(); openDetail(act, key); }));
                int n = ModuleWishlist.size(ctx);
                String installLabel = act.getString(
                        n > 1 ? R.string.k2go_sheet_install_sel : R.string.k2go_sheet_install);
                content.addView(row(ctx, R.drawable.ic_download_24, installLabel,
                        R.color.k2go_teal, false, v -> { dlg.dismiss(); if (sel) openMapsSetup(act); else openHub(act); }));
                content.addView(row(ctx, R.drawable.ic_close_24, act.getString(R.string.k2go_sheet_cancel),
                        R.color.k2go_clay, false, v -> {
                            if (sel) MapsWishlist.clear(ctx);
                            else if (key != null) ModuleWishlist.remove(ctx, key);
                            dlg.dismiss();
                            if (onChanged != null) onChanged.run();
                        }));
                break;
            }
            case NOT_INSTALLED:
            default:
                content.addView(row(ctx, R.drawable.ic_info_24, act.getString(R.string.k2go_sheet_about),
                        R.color.k2go_ink, true, v -> { dlg.dismiss(); openDetail(act, key); }));
                if (sel) {
                    // maps: installing needs the content selector; route there (schedule lives in the wizard).
                    content.addView(row(ctx, R.drawable.ic_download_24, act.getString(R.string.k2go_sheet_install),
                            R.color.k2go_teal, false, v -> { dlg.dismiss(); openMapsSetup(act); }));
                } else {
                    content.addView(row(ctx, R.drawable.ic_download_24, act.getString(R.string.k2go_sheet_install),
                            R.color.k2go_teal, false, v -> {
                                if (key != null) ModuleWishlist.add(ctx, key);
                                dlg.dismiss();
                                openHub(act);
                            }));
                    content.addView(row(ctx, R.drawable.ic_schedule_24, act.getString(R.string.k2go_sheet_schedule),
                            R.color.k2go_ink, false, v -> {
                                if (key != null) ModuleWishlist.add(ctx, key);
                                dlg.dismiss();
                                if (onChanged != null) onChanged.run();
                            }));
                }
                break;
        }

        if (state != State.SCHEDULED && key != null) {   // ADFA-4958: Hide declutters Home; Restore lives in management
            content.addView(row(ctx, R.drawable.ic_hide_24, act.getString(R.string.k2go_sheet_hide),
                    R.color.k2go_ink, false, v -> {
                        HiddenModules.add(ctx, key);
                        dlg.dismiss();
                        if (onChanged != null) onChanged.run();
                    }));
        }
        dlg.setContentView(content);
        dlg.show();
    }

    private static View row(Context ctx, int iconRes, String label, int colorRes,
                            boolean chevron, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setMinimumHeight(dp(ctx, 56));
        int p = dp(ctx, 16);
        row.setPadding(p, dp(ctx, 14), p, dp(ctx, 14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        row.setLayoutParams(lp);

        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        ic.setColorFilter(ContextCompat.getColor(ctx, colorRes));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24));
        ilp.rightMargin = dp(ctx, 14);
        row.addView(ic, ilp);

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setTextColor(ContextCompat.getColor(ctx, colorRes));
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        if (chevron) {
            TextView chev = new TextView(ctx);
            chev.setText("›");
            chev.setTextSize(18);
            chev.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
            row.addView(chev);
        }
        return row;
    }

    private static String stateLabel(Activity act, State s) {
        switch (s) {
            case READY: return act.getString(R.string.k2go_state_ready);
            case SCHEDULED: return act.getString(R.string.k2go_state_scheduled);
            case NOT_INSTALLED:
            default: return act.getString(R.string.k2go_state_not_installed);
        }
    }

    private static void openContent(Activity act, String endpoint) {
        Intent i = new Intent(act, org.iiab.controller.PortalActivity.class);
        i.putExtra("TARGET_URL", BoxEndpoints.BASE + "/" + endpoint + "/");
        act.startActivity(i);
    }

    private static void openDetail(Activity act, String key) {
        if (key == null) return;
        act.startActivity(new Intent(act, SetupLibraryActivity.class)
                .putExtra(SetupLibraryActivity.EXTRA_MODULE_DETAIL, key));
    }

    private static void openHub(Activity act) {
        act.startActivity(new Intent(act, SetupLibraryActivity.class)
                .putExtra(SetupLibraryActivity.EXTRA_MODULE_MGMT, true));
    }

    private static void openMapsSetup(Activity act) {
        act.startActivity(new Intent(act, SetupLibraryActivity.class)
                .putExtra(SetupLibraryActivity.EXTRA_MAPS_SETUP, true));
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
