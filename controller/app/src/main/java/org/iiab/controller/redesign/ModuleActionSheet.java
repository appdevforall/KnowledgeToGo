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
import org.iiab.controller.system.domain.Operation;

public final class ModuleActionSheet {

    private ModuleActionSheet() {}

    /**
     * What the sheet is looking at.
     *
     * <p>ADFA-5061: {@link #UNKNOWN} is new, and it is the point. The other three were
     * derived from a Home card's dot, and a card whose endpoint failed to answer read as
     * NOT_INSTALLED — so the app would offer to install a platform that is installed, and
     * during a Kolibri import it offered to install Kolibri while Kolibri was downloading.
     * The Home probe already tells a 404 (really absent) from silence (nothing established);
     * only this sheet was throwing that away. Silence now has its own state, and its actions
     * are the ones that are safe when you do not know.
     */
    public enum State { READY, NOT_INSTALLED, SCHEDULED, UNKNOWN }

    /**
     * How prominent a row is. Colour only — the severity channel, per the convention the Home
     * card established: neutral or accented for information, and one colour for the action that
     * throws something away.
     *
     * <p>ADFA-5061: this replaced a four-valued {@code Tone} that also carried LIVE and STOPPED.
     * A review caught that for what it was — the execution class typed by hand into a view
     * class, at four call sites, while {@code Operation.appInstall(key)} was in scope at every
     * one of them. Worse, it collapsed two orthogonal axes into one enum and then mapped LIVE
     * and STOPPED to the same colour to undo the collapse. Severity is a property of the row;
     * the class is a property of the operation. They are asked separately now.
     */
    private enum Emphasis { ACCENT, PLAIN, DESTRUCTIVE }

    private static int colorOf(Emphasis e) {
        switch (e) {
            case ACCENT:      return R.color.k2go_teal;
            case DESTRUCTIVE: return R.color.k2go_clay;
            default:          return R.color.k2go_ink;
        }
    }

    /**
     * The consequence a row has to state before it is tapped, read from the operation.
     *
     * <p>Exactly one of this sheet's rows corresponds to an operation on the box: Install, which
     * is {@code Operation.appInstall}. Open navigates to a platform that is already running —
     * that is not an operation, it is a consequence of one — and About, Schedule, Hide and
     * Cancel write a preference and nothing else. So there is one question here and the model
     * answers it: an operation that is not live takes the services down while it runs, and the
     * user should hear that from the row rather than discover it from the screen it leads to.
     */
    private static Integer noticeFor(Operation op) {
        return op != null && !op.isLive() ? R.string.k2go_sheet_install_note : null;
    }

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
        StringBuilder sub = new StringBuilder();
        if (!project.isEmpty()) sub.append(project).append("  ·  ");
        sub.append(stateLabel(act, state));
        // ADFA-4958 §5.3 put the download size here unconditionally. ADFA-5061: only while it
        // is still a price — which means only where there is something to buy. On an installed
        // platform that cost was paid once, and repeating it reads as something still owed; in
        // UNKNOWN there is no Install row for it to attach to and the platform is probably
        // installed, which is the whole argument for that state. Named states rather than
        // "not READY", so a fifth state has to decide for itself.
        if (state == State.NOT_INSTALLED || state == State.SCHEDULED) {
            int sizeRes = ModuleCards.sizeLabelRes(key);
            long bytes = ModuleSizes.bytesFor(ctx, key);
            if (sizeRes != 0) sub.append("  ·  ").append(act.getString(sizeRes));
            else if (bytes >= 0) sub.append("  ·  ≈ ").append(org.iiab.controller.util.ByteFormatter.toHuman(bytes));
        }
        subtitle.setText(sub.toString());
        subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        subtitle.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        hcol.addView(subtitle);
        LinearLayout.LayoutParams hcolp = new LinearLayout.LayoutParams(0, -2, 1f);
        header.addView(hcol, hcolp);
        LinearLayout.LayoutParams headlp = new LinearLayout.LayoutParams(-1, -2);
        headlp.bottomMargin = dp(ctx, 8);
        content.addView(header, headlp);

        // One vocabulary of actions; the state chooses which of them appear. Nothing below
        // decides how a row looks — Tone does, in one place.
        final View about = row(ctx, R.drawable.ic_info_24, act.getString(R.string.k2go_sheet_about),
                Emphasis.PLAIN, null, true, v -> { dlg.dismiss(); openDetail(act, key); });

        switch (state) {
            case READY:
                content.addView(row(ctx, R.drawable.ic_arrow_right, act.getString(R.string.k2go_sheet_open),
                        Emphasis.ACCENT, null, false, v -> { dlg.dismiss(); openContent(act, endpoint); }));
                content.addView(about);
                break;
            case SCHEDULED: {
                content.addView(about);
                int n = ModuleWishlist.size(ctx);
                String installLabel = act.getString(
                        n > 1 ? R.string.k2go_sheet_install_sel : R.string.k2go_sheet_install);
                content.addView(row(ctx, R.drawable.ic_download_24, installLabel,
                        Emphasis.ACCENT, Operation.appInstall(key), false, v -> { dlg.dismiss(); if (sel) openMapsSetup(act); else openHub(act); }));
                content.addView(row(ctx, R.drawable.ic_close_24, act.getString(R.string.k2go_sheet_cancel),
                        Emphasis.DESTRUCTIVE, null, false, v -> {
                            if (sel) MapsWishlist.clear(ctx);
                            else if (key != null) ModuleWishlist.remove(ctx, key);
                            dlg.dismiss();
                            if (onChanged != null) onChanged.run();
                        }));
                break;
            }
            case UNKNOWN:
                // Nothing was established about the platform, which is not the same as it not
                // being there. Install is withheld rather than guessed — offering it over an
                // installed platform is how this state was noticed.
                //
                // Schedule is offered, and a first version withheld it too. That was collateral:
                // it writes ModuleWishlist and nothing else, it is undone by Cancel schedule, and
                // with the box stopped every card is in this state — so withholding it left the
                // most natural thing to do from a stopped box, queue an install for later, with
                // no way to ask for it. The asymmetry is the point: guessing wrong about Install
                // starts a runrole nobody wanted; guessing wrong about Schedule costs a
                // preference the user can clear.
                content.addView(about);
                if (!sel && key != null) {
                    content.addView(row(ctx, R.drawable.ic_schedule_24, act.getString(R.string.k2go_sheet_schedule),
                            Emphasis.PLAIN, null, false, v -> {
                                ModuleWishlist.add(ctx, key);
                                dlg.dismiss();
                                if (onChanged != null) onChanged.run();
                            }));
                }
                break;
            case NOT_INSTALLED:
            default:
                content.addView(about);
                if (sel) {
                    // maps: installing needs the content selector; route there (schedule lives in the wizard).
                    content.addView(row(ctx, R.drawable.ic_download_24, act.getString(R.string.k2go_sheet_install),
                            Emphasis.ACCENT, Operation.appInstall(key), false, v -> { dlg.dismiss(); openMapsSetup(act); }));
                } else {
                    content.addView(row(ctx, R.drawable.ic_download_24, act.getString(R.string.k2go_sheet_install),
                            Emphasis.ACCENT, Operation.appInstall(key), false, v -> {
                                if (key != null) ModuleWishlist.add(ctx, key);
                                dlg.dismiss();
                                openHub(act);
                            }));
                    content.addView(row(ctx, R.drawable.ic_schedule_24, act.getString(R.string.k2go_sheet_schedule),
                            Emphasis.PLAIN, null, false, v -> {
                                if (key != null) ModuleWishlist.add(ctx, key);
                                dlg.dismiss();
                                if (onChanged != null) onChanged.run();
                            }));
                }
                break;
        }

        if (state != State.SCHEDULED && key != null) {   // ADFA-4958: Hide declutters Home; Restore lives in management
            content.addView(row(ctx, R.drawable.ic_hide_24, act.getString(R.string.k2go_sheet_hide),
                    Emphasis.PLAIN, null, false, v -> {
                        HiddenModules.add(ctx, key);
                        dlg.dismiss();
                        if (onChanged != null) onChanged.run();
                    }));
        }
        // ADFA-5061: scrollable. The sheet was a plain LinearLayout in a FrameLayout, so its
        // tallest configuration — About, a two-line Install, Schedule and Hide under a header
        // whose subtitle can wrap in a verbose locale — clipped its bottom rows in landscape and
        // at large font scales, with no way to reach them. Adding a row made that reachable
        // rather than theoretical, and the fix belongs here rather than in a row budget.
        androidx.core.widget.NestedScrollView scroller = new androidx.core.widget.NestedScrollView(ctx);
        scroller.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        dlg.setContentView(scroller);
        dlg.show();
    }

    private static View row(Context ctx, int iconRes, String label, Emphasis emphasis,
                            Operation op, boolean chevron, View.OnClickListener onClick) {
        final int colorRes = colorOf(emphasis);
        final Integer noteRes = noticeFor(op);
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

        // The label, and under it the consequence when the action has one. A column rather
        // than a single line so the note reads as belonging to this row and not to the sheet.
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setTextColor(ContextCompat.getColor(ctx, colorRes));
        col.addView(t);
        if (noteRes != null) {
            TextView note = new TextView(ctx);
            note.setText(noteRes);
            note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            note.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
            col.addView(note);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

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
            case UNKNOWN: return act.getString(R.string.k2go_state_no_answer);
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
