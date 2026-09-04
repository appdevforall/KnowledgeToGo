/*
 * ============================================================================
 * Name        : ModuleDetailFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. Module detail (Play Store style): 16:9 image, title + subtitle, and a
 *               description of what the module does. Primary "Schedule install" banks the module to
 *               ModuleWishlist and returns to the hub (✓); secondary "Install now" banks it and
 *               proceeds to the install index immediately. No content selector (maps is the only
 *               module that will add one, later) — the description replaces it. Reachable directly
 *               (deep-link) from the hub or, later, from Get More.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.util.Snackbars;

public class ModuleDetailFragment extends Fragment {

    private static final String ARG_KEY = "yamlKey";

    public static ModuleDetailFragment newInstance(String yamlBaseKey) {
        ModuleDetailFragment f = new ModuleDetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_KEY, yamlBaseKey);
        f.setArguments(b);
        return f;
    }

    private String key;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_module_detail, container, false);
        key = getArguments() != null ? getArguments().getString(ARG_KEY) : null;
        final ModuleCards.Card c = ModuleCards.byKey(key);
        if (c == null) {   // unknown module — nothing to show
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return root;
        }

        TextView back = root.findViewById(R.id.k2go_moddet_back);
        back.setText("‹ " + getString(R.string.k2go_mod_back));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        ((ImageView) root.findViewById(R.id.k2go_moddet_image)).setImageResource(c.imageRes);
        ((TextView) root.findViewById(R.id.k2go_moddet_title)).setText(c.detailTitleRes);
        ((TextView) root.findViewById(R.id.k2go_moddet_sub)).setText(c.subRes);
        ((TextView) root.findViewById(R.id.k2go_moddet_desc)).setText(c.descRes);

        // ADFA-4958: meta chips (size / version / Runs offline), "What it includes", and license.
        // ADFA-5011: FlowLayout (typed as ViewGroup) so the chips wrap to a 2nd line instead of clipping.
        ViewGroup chips = root.findViewById(R.id.k2go_moddet_chips);
        int sizeRes = ModuleCards.sizeLabelRes(c.key());   // ADFA-4958: maps uses a curated "200 MB+" floor
        long bytes = ModuleSizes.bytesFor(requireContext(), c.key());
        String sizeText = (sizeRes != 0) ? getString(sizeRes)
                : (bytes >= 0) ? "\u2248 " + org.appdevforall.k2go.util.ByteFormatter.toHuman(bytes)
                : "\u2248 NA";
        // K2GO-385 (PR3): size / version are neutral metadata tags (colour is noise); "Runs offline" is a
        // leaf trait (a capability that carries valence). The lifecycle status below is a dot+text badge.
        chips.addView(K2GoChip.create(requireContext(), sizeText));
        String ver = ModuleCards.version(c.key());
        if (ver != null) chips.addView(K2GoChip.create(requireContext(), "v" + ver));
        chips.addView(K2GoChip.create(requireContext(), getString(R.string.k2go_mod_runs_offline), R.color.k2go_leaf));
        if (ModuleCards.isDemo(c.key())) chips.addView(K2GoChip.create(requireContext(), getString(R.string.k2go_mod_demo), R.color.k2go_amber_text));   // ADFA-4958

        int inc = ModuleCards.includesRes(c.key());
        if (inc != 0) {
            ((TextView) root.findViewById(R.id.k2go_moddet_includes_body)).setText(inc);
        } else {
            root.findViewById(R.id.k2go_moddet_includes).setVisibility(View.GONE);
        }

        String lic = ModuleCards.license(c.key());
        TextView licView = root.findViewById(R.id.k2go_moddet_license);
        if (lic != null) licView.setText(getString(R.string.k2go_mod_license_fmt, lic));
        else licView.setVisibility(View.GONE);

        // ADFA-5104: the detail offered Install now and Schedule on a module that is already
        // installed. The hub stopped doing that; this screen is one tap behind it, and it is the
        // louder of the two — a full-width button reads as an invitation in a way a checkbox does
        // not. Both actions go, and a chip says why.
        //
        // Read off the main thread and applied when it returns: the buttons start hidden rather
        // than start visible, because showing them and then taking them away is how a user ends
        // up tapping one in the gap.
        final Button installNowBtn = root.findViewById(R.id.k2go_moddet_install_now);
        final ViewGroup chipRow = chips;
        installNowBtn.setVisibility(View.GONE);

        Button schedule = root.findViewById(R.id.k2go_moddet_schedule);
        schedule.setVisibility(View.GONE);

        final android.content.Context appCtx = requireContext().getApplicationContext();
        org.appdevforall.k2go.util.AppExecutors.get().io().execute(() -> {
            final java.util.Set<String> onDisk =
                    org.appdevforall.k2go.system.data.InstalledModulesReader.installedKeys(appCtx);
            // Null is "could not read", not "nothing installed" — same distinction the hub keeps.
            final boolean isInstalled = onDisk != null && onDisk.contains(c.key());
            final boolean unknown = onDisk == null;
            // ADFA-5150/5312: read the system verdict off the same IO thread (it hits the disk). A
            // damaged rootfs with still-readable flags used to show Install now; and during an install
            // the marker made it read "no system" and offer Recover into a system that is actually there
            // and mid-setup. Branch on the one shared verdict instead of bare isSystemInstalled().
            final org.appdevforall.k2go.system.domain.SystemVerdict.State verdict =
                    org.appdevforall.k2go.system.data.SystemFactsReader.verdict(appCtx);
            // root, not requireView(): inside onCreateView the fragment's view is not set yet,
            // so requireView() would throw. root is already inflated and its handler is the
            // main looper's.
            root.post(() -> {
                if (!isAdded()) return;
                if (verdict == org.appdevforall.k2go.system.domain.SystemVerdict.State.NO_SYSTEM
                        || verdict == org.appdevforall.k2go.system.domain.SystemVerdict.State.DAMAGED) {
                    chipRow.addView(K2GoStatusBadge.create(requireContext(), getString(R.string.k2go_state_no_system), R.color.k2go_amber_text));
                    installNowBtn.setText(R.string.k2go_home_recover);
                    installNowBtn.setOnClickListener(v -> SetupLibraryActivity.recover(requireContext()));
                    installNowBtn.setVisibility(View.VISIBLE);
                    return;   // no Install/Schedule into a system that is not there
                }
                if (verdict == org.appdevforall.k2go.system.domain.SystemVerdict.State.INSTALLING
                        || verdict == org.appdevforall.k2go.system.domain.SystemVerdict.State.CLONE_RECEIVING
                        || verdict == org.appdevforall.k2go.system.domain.SystemVerdict.State.CLONE_SHARING) {
                    // ADFA-5312: a system op is in progress — the system is present but mid-setup and the
                    // server is down. Don't offer Install or Recover into it; just say it's busy.
                    chipRow.addView(K2GoStatusBadge.create(requireContext(), getString(R.string.k2go_home_installing), R.color.k2go_amber_text));
                    return;
                }
                if (isInstalled) {
                    chipRow.addView(K2GoStatusBadge.create(requireContext(), getString(R.string.k2go_mod_phase_done), R.color.k2go_leaf));
                    return;   // nothing to offer: a module cannot be uninstalled or reinstalled here
                }
                // ADFA-4898: this module's runrole failed in the last finished batch — the SAME per-module
                // didFail(key) that colours the hub's "Couldn't install" pill, so only the module that
                // actually failed lands here (a batch where one of many failed does not turn the rest into
                // Retry). Installed outranks it (a module genuinely on disk is not "failed"), which is why
                // this sits just below the isInstalled branch. The two action buttons take on the recovery
                // roles: primary Schedule -> Retry (re-fires just this module, user-confirmed, no auto-retry),
                // secondary Install now -> Back — the same slot-reuse this switch already does for Recover.
                // Retry then bounces to the hub, which observes the queue live; this detail is a one-shot
                // snapshot (no observer) and would otherwise sit on a stale "Couldn't install".
                if (org.appdevforall.k2go.install.presentation.ModuleQueueRepository.get().current().didFail(c.key())) {
                    chipRow.addView(K2GoStatusBadge.create(requireContext(), getString(R.string.k2go_mod_phase_failed), R.color.k2go_clay));
                    schedule.setText(R.string.k2go_home_retry);
                    // Shared, busy-gated retry (same action the live progress card fires). On a real start,
                    // land on the install index — the same destination as a normal install (openModuleIndex)
                    // — where the batch we just re-fired shows its rows, progress and log. NOT onBackPressed:
                    // that dropped the user on the hub, which during an install only shows "Adding content"
                    // with no route to the progress (the bug seen retrying from Module management).
                    schedule.setOnClickListener(v -> {
                        if (ModuleRetry.fire(v, c.key())) {
                            startActivity(new android.content.Intent(requireContext(), SetupProgressActivity.class));
                        }
                    });
                    schedule.setVisibility(View.VISIBLE);
                    installNowBtn.setText(R.string.k2go_setup_back);
                    installNowBtn.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
                    installNowBtn.setVisibility(View.VISIBLE);
                    return;
                }
                if (unknown) {
                    chipRow.addView(K2GoStatusBadge.create(requireContext(), getString(R.string.k2go_state_no_answer),
                            R.color.k2go_amber_text));
                    return;   // no grounds to offer work either way
                }
                installNowBtn.setVisibility(View.VISIBLE);
                if (!c.hasSelector) schedule.setVisibility(View.VISIBLE);
            });
        });

        boolean scheduled = ModuleWishlist.contains(requireContext(), c.key());
        schedule.setText(getString(scheduled ? R.string.k2go_mod_unschedule : R.string.k2go_mod_schedule));
        schedule.setOnClickListener(v -> {
            if (ModuleWishlist.contains(requireContext(), c.key())) ModuleWishlist.remove(requireContext(), c.key());
            else ModuleWishlist.add(requireContext(), c.key());
            requireActivity().getOnBackPressedDispatcher().onBackPressed();   // back to the hub (shows ✓)
        });

        // ADFA-4958: maps schedules via its selector, not here — folded into the callback above,
        // which is now the only thing that makes either button visible.

        Button installNow = installNowBtn;
        installNow.setOnClickListener(v -> {
            if (org.appdevforall.k2go.env.EnvironmentLock.isHeld(requireContext())) { Snackbars.make(v, org.appdevforall.k2go.util.BusyMessage.resFor(requireContext())).show(); return; }
            if (getActivity() instanceof SetupLibraryActivity) {
                if (c.hasSelector) {
                    ((SetupLibraryActivity) getActivity()).openMapsChoose();   // ADFA-4958: maps needs its content selector first
                } else {
                    ModuleWishlist.add(requireContext(), c.key());
                    ((SetupLibraryActivity) getActivity()).openModuleIndex();
                }
            }
        });

        return root;
    }

}
