/*
 * ============================================================================
 * Name        : ModuleHubFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. Module management hub. Mirrors the Get More hub, but INVERTED: it lists
 *               the proot modules you can still install — a card shows only when its module is NOT
 *               already present (probeAll returns not-reachable), and 64-bit-only modules are hidden
 *               on 32-bit. Tapping a card opens its detail (Play Store style); scheduled modules get
 *               a "Scheduled" badge (mirrors Books' picked state). The pinned "Install N selected"
 *               proceeds to the install index, which drains ModuleWishlist through the proot queue.
 *               Entry-point-agnostic: hosted in SetupLibraryActivity, reachable from Settings and
 *               (later) Get More via the same launch.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;
import org.iiab.controller.util.Snackbars;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModuleHubFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> installable = new HashSet<>();   // yamlBaseKeys not yet installed
    private int probesPending = 0;
    private int probeGen = 0;   // ADFA-4842: supersedes an in-flight probe batch (e.g. onResume re-probe)
    private LinearLayout host;
    private Button proceed;

    /** ADFA-4958 §5.2: outlined state pill (transparent fill, state-colored 1.4dp stroke, full radius). */
    private TextView statePill(String text, int colorRes) {
        int color = ContextCompat.getColor(requireContext(), colorRes);
        TextView pill = new TextView(requireContext());
        pill.setText(text);
        pill.setTextColor(color);
        pill.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        pill.setPadding(px(10), px(3), px(10), px(3));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setCornerRadius(px(20));
        int strokeW = Math.max(1, Math.round(1.4f * getResources().getDisplayMetrics().density));
        bg.setStroke(strokeW, color);
        pill.setBackground(bg);
        return pill;
    }

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    private static boolean is64Bit() {
        return Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_module_hub, container, false);
        host = root.findViewById(R.id.k2go_mod_cards);
        proceed = root.findViewById(R.id.k2go_mod_proceed);

        TextView back = root.findViewById(R.id.k2go_mod_back);
        back.setText(getString(R.string.k2go_back_link));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        proceed.setOnClickListener(v -> {
            if (org.iiab.controller.env.EnvironmentLock.isHeld(requireContext())) { Snackbars.make(v, R.string.k2go_install_busy).show(); return; }
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openModuleIndex();
            }
        });

        buildCards();   // shows "checking…" until probes resolve
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        probeAll();          // re-probe: reflects modules installed since we were last shown
        refreshProceed();    // scheduled set may have changed (returned from a detail)
    }

    /** Probe every presentable module's endpoint; a module is INSTALLABLE when it is NOT reachable
     *  (not installed yet) and its 64-bit requirement is met. */
    private void probeAll() {
        final int gen = ++probeGen;   // supersede any batch still in flight; its callbacks will bail
        List<ModuleCards.Card> cards = ModuleCards.all();
        final Set<String> found = new HashSet<>();
        probesPending = 0;
        for (final ModuleCards.Card c : cards) {
            if (c.requires64Bit() && !is64Bit()) continue;   // hidden on this device
            probesPending++;
            final String key = c.key();
            final String endpoint = c.endpoint();
            AppExecutors.get().io().execute(() -> {
                final boolean installed = reachable(endpoint);
                main.post(() -> {
                    if (!isAdded() || gen != probeGen) return;   // fragment gone or batch superseded
                    if (!installed) found.add(key);
                    probesPending--;
                    if (probesPending <= 0) {
                        installable.clear();
                        installable.addAll(found);
                        buildCards();
                        refreshProceed();
                    }
                });
            });
        }
        if (probesPending == 0) { installable.clear(); buildCards(); }
    }

    private void buildCards() {
        if (host == null) return;
        host.removeAllViews();
        TextView helper = new TextView(requireContext());   // ADFA-4958
        helper.setText(R.string.k2go_mod_mgmt_helper);
        helper.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        helper.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        LinearLayout.LayoutParams helperLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        helperLp.bottomMargin = px(8);
        host.addView(helper, helperLp);

        // ADFA-5011: the dash-node REST core is a system module — always present (not installable/
        // removable), with a single Rebuild action. Shown at the top, above the installable list.
        addSystemDashboardCard();

        List<ModuleCards.Card> items = new ArrayList<>();
        for (ModuleCards.Card c : ModuleCards.all()) if (installable.contains(c.key())) items.add(c);

        if (items.isEmpty()) {
            TextView msg = new TextView(requireContext());
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, px(24), 0, px(24));
            msg.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            msg.setText(probesPending > 0 ? R.string.k2go_gm_checking : R.string.k2go_mod_none);
            host.addView(msg);
        } else {
            // ADFA-4958: last informed step before the locked install index. A build takes time.
            TextView note = new TextView(requireContext());
            note.setText(R.string.k2go_mod_time_note);
            note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            note.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_warn_ink));
            note.setBackgroundResource(R.drawable.k2go_warn_bg);
            note.setPadding(px(16), px(14), px(16), px(14));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.bottomMargin = px(12);
            host.addView(note, nlp);
            for (ModuleCards.Card c : items) host.addView(cardRow(c));
        }
        addHiddenSection();
    }

    // ADFA-4958: HIDDEN -> Restore. Modules can't be uninstalled; Hide only declutters Home and this
    // is where they come back. Lists every hidden module (regardless of install state).
    private void addHiddenSection() {
        if (HiddenModules.isEmpty(requireContext())) return;

        TextView header = new TextView(requireContext());
        header.setText(R.string.k2go_mod_hidden_header);
        header.setAllCaps(true);
        header.setLetterSpacing(0.08f);
        header.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        header.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = px(18); hlp.bottomMargin = px(6);
        host.addView(header, hlp);

        for (final String key : HiddenModules.keys(requireContext())) {
            ModuleCards.Card c = ModuleCards.byKey(key);
            if (c == null) continue;
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.k2go_card_bg);
            row.setPadding(px(16), px(14), px(16), px(14));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = px(12);
            row.setLayoutParams(rlp);

            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(requireContext());
            name.setText(getString(c.titleRes));
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            name.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
            name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            col.addView(name);
            TextView sub = new TextView(requireContext());
            sub.setText(R.string.k2go_mod_hidden_sub);
            sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView restore = statePill(getString(R.string.k2go_mod_restore), R.color.k2go_teal);   // ADFA-4958 §5.7: outlined teal pill
            restore.setPadding(px(14), px(6), px(14), px(6));
            restore.setOnClickListener(v -> { HiddenModules.remove(requireContext(), key); buildCards(); });
            row.addView(restore);
            host.addView(row);
        }

        TextView foot = new TextView(requireContext());
        foot.setText(R.string.k2go_mod_hidden_foot);
        foot.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        foot.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = px(4);
        host.addView(foot, flp);
    }

    /** A full-width tappable module row: title + subtitle + one-line description, with a "Scheduled"
     *  badge when banked, else a chevron. */
    private View cardRow(final ModuleCards.Card c) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openModuleDetail(c.key());
            }
        });

        if (!c.hasSelector) {   // ADFA-4958: tick to schedule several at once (maps uses its own selector)
            com.google.android.material.checkbox.MaterialCheckBox cb =
                    new com.google.android.material.checkbox.MaterialCheckBox(requireContext());
            cb.setChecked(ModuleWishlist.contains(requireContext(), c.key()));
            cb.setOnCheckedChangeListener((b, chk) -> {
                if (chk) ModuleWishlist.add(requireContext(), c.key());
                else ModuleWishlist.remove(requireContext(), c.key());
                refreshProceed();
            });
            LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cblp.rightMargin = px(4);
            row.addView(cb, cblp);
        }

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(requireContext());
        title.setText(getString(c.titleRes));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(title);

        TextView sub = new TextView(requireContext());
        sub.setText(getString(c.subRes));
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        col.addView(sub);

        TextView desc = new TextView(requireContext());
        desc.setText(getString(c.descRes));
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        desc.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        desc.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = px(4);
        col.addView(desc, dlp);

        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        boolean scheduled = ModuleWishlist.contains(requireContext(), c.key());   // ADFA-4958 §5.2: state pill
        TextView pill = statePill(
                scheduled ? getString(R.string.k2go_mod_scheduled)
                          : getString(R.string.k2go_state_not_installed),
                scheduled ? R.color.k2go_teal : R.color.k2go_muted);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = px(10);
        row.addView(pill, tlp);
        return row;
    }

    // ---- ADFA-5011: dash-node "system module" card (Rebuild-only) -------------------------------

    private void addSystemDashboardCard() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(requireContext());
        title.setText(R.string.k2go_dash_card_title);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(title);
        TextView sub = new TextView(requireContext());
        sub.setText(R.string.k2go_dash_version_unknown);
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Tapping the card opens the dashboard detail (what it is + full description); the pill is the
        // quick Rebuild action. Both route rebuilds through the shared DashboardRebuild gate.
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openDashboardDetail();
            }
        });

        TextView rebuild = statePill(getString(R.string.k2go_dash_rebuild), R.color.k2go_teal);
        rebuild.setPadding(px(14), px(6), px(14), px(6));
        rebuild.setOnClickListener(v -> DashboardRebuild.confirmAndStart(this, host, null));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = px(10);
        row.addView(rebuild, tlp);

        host.addView(row);
        fetchDashVersion(sub);
    }

    /** Show the installed dash-node version on the card. Read from the rootfs package.json on disk
     *  (authoritative, always present) rather than the REST endpoint — that endpoint only exists in
     *  newer builds, so on an older install it 404s and the version silently never appeared. */
    private void fetchDashVersion(final TextView sub) {
        final Context ctx = requireContext().getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            final String ver = DashboardVersion.installed(ctx);
            main.post(() -> {
                if (isAdded() && ver != null) sub.setText(getString(R.string.k2go_dash_card_sub_fmt, ver));
            });
        });
    }

    private void refreshProceed() {
        if (proceed == null || !isAdded()) return;
        int n = ModuleWishlist.size(requireContext());
        proceed.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        proceed.setEnabled(n > 0);
        if (n > 0) proceed.setText(getString(R.string.k2go_mod_proceed_fmt, n));
    }

    /** A module is "installed" when its server endpoint answers (same test Get More uses). */
    private static boolean reachable(String endpoint) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(BoxEndpoints.BASE + "/" + endpoint + "/");
            conn = (HttpURLConnection) u.openConnection();
            conn.setUseCaches(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
