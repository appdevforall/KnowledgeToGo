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
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;
import org.iiab.controller.util.Snackbars;

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
                : (bytes >= 0) ? "\u2248 " + org.iiab.controller.util.ByteFormatter.toHuman(bytes)
                : "\u2248 NA";
        chips.addView(chip(sizeText, R.color.k2go_teal));
        String ver = ModuleCards.version(c.key());
        if (ver != null) chips.addView(chip("v" + ver, R.color.k2go_teal));
        chips.addView(chip(getString(R.string.k2go_mod_runs_offline), R.color.k2go_leaf));
        if (ModuleCards.isDemo(c.key())) chips.addView(chip(getString(R.string.k2go_mod_demo), R.color.k2go_amber_text));   // ADFA-4958

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
        org.iiab.controller.util.AppExecutors.get().io().execute(() -> {
            final java.util.Set<String> onDisk =
                    org.iiab.controller.system.data.InstalledModulesReader.installedKeys(appCtx);
            // Null is "could not read", not "nothing installed" — same distinction the hub keeps.
            final boolean isInstalled = onDisk != null && onDisk.contains(c.key());
            final boolean unknown = onDisk == null;
            // root, not requireView(): inside onCreateView the fragment's view is not set yet,
            // so requireView() would throw. root is already inflated and its handler is the
            // main looper's.
            root.post(() -> {
                if (!isAdded()) return;
                if (isInstalled) {
                    chipRow.addView(chip(getString(R.string.k2go_mod_phase_done), R.color.k2go_leaf));
                    return;   // nothing to offer: a module cannot be uninstalled or reinstalled here
                }
                if (unknown) {
                    chipRow.addView(chip(getString(R.string.k2go_state_no_answer),
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
            if (org.iiab.controller.env.EnvironmentLock.isHeld(requireContext())) { Snackbars.make(v, R.string.k2go_install_busy).show(); return; }
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

    /** A small outlined pill for the meta-chip row (size / version / Runs offline). */
    private TextView chip(String text, int colorRes) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        int color = ContextCompat.getColor(requireContext(), colorRes);   // ADFA-4958 §5.4: outlined pill (teal-on-teal fill was invisible)
        t.setTextColor(color);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setCornerRadius(11 * d);
        bg.setStroke(Math.max(1, Math.round(1.4f * d)), color);
        t.setBackground(bg);
        int hp = Math.round(10 * d), vp = Math.round(5 * d);
        t.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(8 * d);
        t.setLayoutParams(lp);
        return t;
    }
}
