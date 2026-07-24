/*
 * ============================================================================
 * Name        : MapsPreparingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (slice 3). Maps Preparing — the boot "getting ready" Lottie
 *               reduced to a fraction of the screen, plus a progress bar and a phase
 *               checklist. Drives a MOCK advancing state for now; the real progress comes
 *               from the REST/Ansible backend later. "Run in background" leaves it running
 *               and returns to the app.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

import org.iiab.controller.R;

public class MapsPreparingFragment extends Fragment {

    private final int[] PHASES = {
            R.string.k2go_maps_phase_prepared,
            R.string.k2go_maps_phase_downloading,
            R.string.k2go_maps_phase_building,
            R.string.k2go_maps_phase_finishing,
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable tick;
    private int prog = 0;
    private ProgressBar bar;
    private TextView label, pct;
    private LinearLayout phases;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_preparing, container, false);

        LottieAnimationView anim = root.findViewById(R.id.k2go_prep_anim);
        try {
            anim.setAnimation(R.raw.library_animation);
            anim.setMinAndMaxFrame("A_ENTRY_LOOP");
            anim.setRepeatCount(LottieDrawable.INFINITE);
            anim.playAnimation();
        } catch (Exception ignore) { /* animation optional */ }

        bar = root.findViewById(R.id.k2go_prep_bar);
        label = root.findViewById(R.id.k2go_prep_label);
        pct = root.findViewById(R.id.k2go_prep_pct);
        phases = root.findViewById(R.id.k2go_prep_phases);

        root.findViewById(R.id.k2go_prep_run_bg).setOnClickListener(v -> requireActivity().finish());

        startMock();
        return root;
    }

    private void startMock() {
        prog = 0;
        tick = new Runnable() {
            @Override
            public void run() {
                prog = Math.min(100, prog + 4);
                int active = prog >= 100 ? PHASES.length
                        : (prog < 10 ? 0 : prog < 70 ? 1 : prog < 95 ? 2 : 3);
                pct.setText(prog + "%");
                bar.setProgress(prog);
                label.setText(getString(prog >= 100 ? R.string.k2go_maps_phase_ready : PHASES[active]));
                drawPhases(active);
                if (prog < 100) main.postDelayed(this, 400);
            }
        };
        main.post(tick);
    }

    private void drawPhases(int active) {
        phases.removeAllViews();
        for (int i = 0; i < PHASES.length; i++) {
            int dotColor = i < active ? R.color.k2go_leaf : (i == active ? R.color.k2go_teal : R.color.k2go_hairline);
            int textColor = i <= active ? R.color.k2go_ink : R.color.k2go_muted;

            LinearLayout r = new LinearLayout(requireContext());
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(5), 0, px(5));

            View dot = new View(requireContext());
            dot.setBackgroundResource(R.drawable.k2go_dot);
            dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), dotColor)));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(10), px(10));
            dlp.rightMargin = px(10);
            r.addView(dot, dlp);

            TextView t = new TextView(requireContext());
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            t.setText(getString(PHASES[i]));
            t.setTextColor(ContextCompat.getColor(requireContext(), textColor));
            r.addView(t);

            phases.addView(r);
        }
    }

    @Override
    public void onDestroyView() {
        if (tick != null) main.removeCallbacks(tick);
        super.onDestroyView();
    }
}
