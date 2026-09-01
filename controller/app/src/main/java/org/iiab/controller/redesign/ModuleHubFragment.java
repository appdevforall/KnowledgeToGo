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
    /**
     * ADFA-5104: yamlBaseKeys the installer's flags say are installed.
     *
     * <p>Read from {@code local_vars.yml} on disk rather than derived from who answers, so it is
     * right with the box stopped. A module install only ever adds, so within one system's life
     * this can grow and cannot shrink — and a system replacement takes the file with it, so the
     * answer invalidates itself.
     */
    private final Set<String> installed = new HashSet<>();
    /**
     * ADFA-5104: whether there is a system to install modules into.
     *
     * <p>The precondition nobody was asking. A module install lays files into the rootfs and runs
     * a runrole inside it; with no rootfs there is nothing to install into and nothing to run it
     * with. The screen still listed all six as installable, because it decided that from a probe
     * — and with no system nothing answers, so everything looked missing.
     *
     * <p>Reachable, not theoretical — and it stays reachable after ADFA-5137. That ticket removed the
     * flag this note used to blame ({@code setup_complete}, written true by four sites and cleared by
     * none), so a device that simply has no system now opens the wizard instead of the Library. What
     * still lands here is the case where a marker is held: a failed restore keeps it, so the launch
     * treats the device as having something on the way, reaches the Library, and this screen can be
     * opened over a rootfs that is empty or half-written. The precondition still has to be asked here.
     *
     * <p>Seeded true so the first frame looks like the ordinary case; the background pass
     * corrects it before anything is offered.
     */
    private boolean systemPresent = true;
    /**
     * ADFA-5104: whether the first disk pass has answered.
     *
     * <p>Without it the list renders every module as "Not installed" for the instant before the
     * read returns and then corrects itself — a flicker that says the wrong thing, which is what
     * this screen is being fixed for. "Checking" is the honest word for that instant.
     */
    private boolean loaded = false;
    /**
     * ADFA-5104: true when a system exists but its flags could not be read.
     *
     * <p>Its own state, not folded into "nothing installed". A half-written or unreadable
     * local_vars.yml is silence, and reading silence as absence is the defect this screen is
     * being fixed for — collapsing it here would have reproduced it one layer down.
     */
    private boolean stateUnknown = false;
    private int probesPending = 0;
    private int probeGen = 0;   // ADFA-4842: supersedes an in-flight probe batch (e.g. onResume re-probe)
    private boolean lastQueueRunning = false;   // ADFA-5312: react only to running-transitions of the module queue
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
            if (org.iiab.controller.env.EnvironmentLock.isHeld(requireContext())) { Snackbars.make(v, org.iiab.controller.util.BusyMessage.resFor(requireContext())).show(); return; }
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openModuleIndex();
            }
        });

        buildCards();   // shows "checking…" until probes resolve

        // ADFA-5312: re-probe when the module queue starts/stops so the "installing" state appears and
        // clears live (the screen otherwise only re-probes on resume). React to running-transitions
        // only, not the per-second percent ticks — a fresh probe is needed on finish to re-read the
        // now-cleared install marker (systemPresent flips back to true).
        org.iiab.controller.install.presentation.ModuleQueueRepository.get().state()
                .observe(getViewLifecycleOwner(), st -> {
                    boolean running = st != null && st.isRunning();
                    if (running != lastQueueRunning) { lastQueueRunning = running; probeAll(); }
                });
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        probeAll();          // re-probe: reflects modules installed since we were last shown
        refreshProceed();    // scheduled set may have changed (returned from a detail)
    }

    /**
     * ADFA-5104: one pass that asks the disk, then lets a probe add to it.
     *
     * <p>It used to be N probes and nothing else, so the screen's answer to "what is installed"
     * was really "what is answering" — and with the box stopped that is nothing, which is how an
     * installed module came to be offered for installation.
     */
    private void probeAll() {
        final int gen = ++probeGen;   // supersede any batch still in flight; its callbacks will bail
        final Context appCtx = requireContext().getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            // Both reads touch the filesystem and neither needs the network or a proot.
            final boolean present =
                    org.iiab.controller.SystemStateEvaluator.isSystemInstalled(appCtx);
            // Null means the flags could not be read; empty means they were read and claim
            // nothing. The screen must not treat those alike.
            final Set<String> fromDisk = present
                    ? org.iiab.controller.system.data.InstalledModulesReader.installedKeys(appCtx)
                    : java.util.Collections.<String>emptySet();
            main.post(() -> {
                if (!isAdded() || gen != probeGen) return;
                systemPresent = present;
                loaded = true;
                stateUnknown = present && fromDisk == null;
                installed.clear();
                if (fromDisk != null) installed.addAll(fromDisk);
                // ADFA-5104: an order for something already installed is moot, and leaving it
                // banked means the button counts it and the drain would rebuild what is there.
                // The pill says "Installed"; the button has to agree.
                for (String key : installed) ModuleWishlist.remove(requireContext(), key);
                probesPending = 0;
                buildCards();       // the disk answer is already showable; do not wait on HTTP
                refreshProceed();
                // A probe can still rescue the unknown case: it only ever adds, so whatever
                // answers is real even when the file could not be read.
                if (present) confirmByProbe(gen);
            });
        });
    }

    /**
     * Ask the running box, and let it <em>add</em> only.
     *
     * <p>A probe that answers proves a platform is there, which is worth having: it covers
     * anything installed by a route that did not write the flag. A probe that 404s proves much
     * less — the service can be installed and switched off, and nginx answers the same way — so
     * it does not demote a module the installer's own flags claim.
     *
     * <p>That leaves one known gap, deliberately: {@code InstallService} writes the flag before
     * the runrole and reverts it if the run fails, so a process death between the two leaves a
     * claim that never came true, and nothing here will correct it. The alternative is letting a
     * 404 demote, and that costs more — it is the error that offers a multi-hour reinstall of
     * something already present, which is the defect this ticket exists to remove.
     */
    private void confirmByProbe(final int gen) {
        final Set<String> answered = new HashSet<>();
        for (final ModuleCards.Card c : ModuleCards.all()) {
            if (c.requires64Bit() && !is64Bit()) continue;   // hidden on this device
            if (installed.contains(c.key())) continue;       // disk already says yes
            probesPending++;
            final String key = c.key();
            final String endpoint = c.endpoint();
            AppExecutors.get().io().execute(() -> {
                final boolean present = reachable(endpoint);
                main.post(() -> {
                    if (!isAdded() || gen != probeGen) return;
                    if (present) answered.add(key);
                    probesPending--;
                    if (probesPending <= 0 && !answered.isEmpty()) {
                        installed.addAll(answered);
                        buildCards();
                        refreshProceed();
                    }
                });
            });
        }
    }

    private void buildCards() {
        if (host == null) return;
        host.removeAllViews();
        // ADFA-5312: branch on the ONE shared system verdict instead of bare systemPresent, so this
        // screen can't offer Recover / Install-a-system over a system that is present and mid-install
        // (the runrole stops the server, so systemPresent reads false during a legitimate install).
        switch (org.iiab.controller.system.data.SystemFactsReader.verdict(requireContext())) {
            case INSTALLING:
            case CLONE_RECEIVING:
            case CLONE_SHARING: {
                // A system op is in progress — show it and do NOT offer Recover.
                TextView installing = new TextView(requireContext());
                installing.setGravity(Gravity.CENTER);
                installing.setPadding(px(8), px(24), px(8), px(24));
                installing.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
                installing.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
                String mod = org.iiab.controller.install.presentation.ModuleQueueRepository.get().currentModule();
                installing.setText(mod != null && !mod.isEmpty()
                        ? getString(R.string.install_status_installing_module, mod)
                        : getString(R.string.k2go_home_installing));
                host.addView(installing);
                return;
            }
            case NO_SYSTEM:
            case DAMAGED: {
                // ADFA-5104: with no usable system there is nothing to install into, so nothing is
                // offered and the screen says why. ADFA-5150: give the one action that helps — Recover
                // (restore / internet / clone all live there).
                TextView msg = new TextView(requireContext());
                msg.setGravity(Gravity.CENTER);
                msg.setPadding(px(8), px(24), px(8), px(24));
                msg.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
                msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
                msg.setText(R.string.k2go_mod_needs_system);
                host.addView(msg);
                com.google.android.material.button.MaterialButton recover =
                        new com.google.android.material.button.MaterialButton(requireContext());
                recover.setText(R.string.k2go_home_recover);
                recover.setOnClickListener(v -> SetupLibraryActivity.recover(requireContext()));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.gravity = Gravity.CENTER;
                host.addView(recover, rlp);
                addHiddenSection();
                return;
            }
            case READY:
                break;   // build the module grid below
        }

        TextView helper = new TextView(requireContext());   // ADFA-4958
        helper.setText(R.string.k2go_mod_mgmt_helper);
        helper.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        helper.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        LinearLayout.LayoutParams helperLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        helperLp.bottomMargin = px(8);
        host.addView(helper, helperLp);

        // ADFA-5011: the dash-node REST core is a system module — present once a system is
        // (not installable/removable), with a single Rebuild action. Shown at the top.
        addSystemDashboardCard();

        // ADFA-5104: everything presentable, with its state. Hiding installed modules answered a
        // question nobody asked — "what can I still add" — while users were asking the other one,
        // "what do I have". A module cannot be uninstalled, so the row is not an offer to undo
        // anything; it is the only place the answer exists.
        List<ModuleCards.Card> items = new ArrayList<>();
        for (ModuleCards.Card c : ModuleCards.all()) {
            if (c.requires64Bit() && !is64Bit()) continue;   // never going to run here
            items.add(c);
        }
        boolean anyInstallable = false;
        for (ModuleCards.Card c : items) if (!installed.contains(c.key())) { anyInstallable = true; break; }

        // ADFA-5104: k2go_mod_none is gone from here. It said "everything available is already
        // installed", which was true while the list was filtered to installables and is not now
        // that the list shows everything — and with the filter gone the branch could only fire on
        // a device where every module needs 64 bits, where the sentence would be wrong anyway.
        if (!loaded || items.isEmpty()) {
            TextView msg = new TextView(requireContext());
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, px(24), 0, px(24));
            msg.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            msg.setText(R.string.k2go_gm_checking);
            host.addView(msg);
        } else {
            // ADFA-4958: last informed step before the locked install index. A build takes time.
            // ADFA-5104: only when something is still installable — over a list of modules that
            // are all installed, the warning describes work nobody is about to start.
            if (anyInstallable && !stateUnknown) {
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
            }
            for (ModuleCards.Card c : items) {
                host.addView(cardRow(c, installed.contains(c.key()), stateUnknown));
            }
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
    private View cardRow(final ModuleCards.Card c, final boolean isInstalled,
                         final boolean unknown) {
        // ADFA-4898: did this module's runrole fail in the last finished batch? (DONE + in failedModules)
        final boolean failed = !isInstalled
                && org.iiab.controller.install.presentation.ModuleQueueRepository.get().current().didFail(c.key());
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

        // ADFA-5104: no tick on an installed module. There is nothing to schedule — installing it
        // again is not an action the app offers, and a checkbox that does nothing is worse than
        // no checkbox. The row still opens its detail, which is where "what is this" lives.
        // ADFA-5104: and no tick when the flags could not be read either. Ticking would bank an
        // order we have no grounds to take.
        if (!isInstalled && !unknown && !failed && !c.hasSelector) {   // ADFA-4958: tick to schedule several at once (maps uses its own selector). ADFA-4898: a failed module shows Retry, not the checkbox.
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

        // ADFA-4958 §5.2: state pill. ADFA-5104: installed outranks scheduled — a module that is
        // already there cannot meaningfully be waiting to be installed, and if a stale wishlist
        // entry survived an install, saying "Scheduled" over it would be the older lie again.
        boolean scheduled = !isInstalled && !unknown && ModuleWishlist.contains(requireContext(), c.key());
        TextView pill = statePill(
                isInstalled ? getString(R.string.k2go_mod_phase_done)
                        : failed ? getString(R.string.k2go_mod_phase_failed)
                                : unknown ? getString(R.string.k2go_state_no_answer)
                                        : scheduled ? getString(R.string.k2go_mod_scheduled)
                                                : getString(R.string.k2go_state_not_installed),
                isInstalled ? R.color.k2go_leaf
                        : failed ? R.color.k2go_clay
                                : unknown ? R.color.k2go_amber_text
                                        : scheduled ? R.color.k2go_teal : R.color.k2go_muted);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = px(10);
        row.addView(pill, tlp);
        // ADFA-4898: Retry does NOT live on the hub row. The "Couldn't install" pill is signal enough
        // to draw the user into the module; the per-module Retry lives on the module detail (the same
        // per-module didFail(key) that colours this pill drives the detail's Retry/Back there), so a
        // batch where only one module failed never sprays Retry across the whole list.
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
        // ADFA-5339: the confirm dialog matches the pill — pass the last-known "update available".
        final boolean[] up = {false};
        rebuild.setOnClickListener(v -> DashboardRebuild.confirmAndStart(this, host, up[0]));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = px(10);
        row.addView(rebuild, tlp);

        host.addView(row);
        fetchDashVersion(sub);
        // ADFA-5339: make the row update-aware through the shared status helper — the pill reads
        // "Update" when a newer build exists, "Rebuild" otherwise (offline stays "Rebuild"; the
        // confirm gate refuses it), and a LIVE update also shows the version arrow in the subtitle
        // (Fork B). fetchDashVersion above set the base installed-version subtitle for every other state.
        DashboardCardStatus.fetch(requireContext(), s -> {
            if (!isAdded()) return;
            up[0] = s.primaryIsUpdate();
            rebuild.setText(s.primaryIsUpdate() ? R.string.k2go_dash_update : R.string.k2go_dash_rebuild);
            if (s.showsVersionArrow()) {
                sub.setText(getString(R.string.k2go_dash_card_sub_update_fmt,
                        s.installedVersion(), s.targetVersion()));
            }
        });
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
        // ADFA-5104: a banked order cannot be drained into a system that is not there. The drain
        // itself now refuses (SystemDoor), but offering the button and then refusing is a worse
        // answer than not offering it.
        if (!systemPresent || stateUnknown) { proceed.setVisibility(View.GONE); return; }
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
