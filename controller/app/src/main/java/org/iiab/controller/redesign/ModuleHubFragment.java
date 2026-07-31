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
            return;
        }

        for (ModuleCards.Card c : items) host.addView(cardRow(c));
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

        boolean scheduled = ModuleWishlist.contains(requireContext(), c.key());
        TextView tail = new TextView(requireContext());
        tail.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        if (scheduled) {
            tail.setText("✓ " + getString(R.string.k2go_mod_scheduled));
            tail.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_leaf));
        } else {
            tail.setText("›");
            tail.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = px(10);
        row.addView(tail, tlp);
        return row;
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
