/*
 * ============================================================================
 * Name        : KolibriSeedingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. Observe-only detail for the Kolibri seeding session,
 *               hosted by SetupProgressActivity. Draws overall progress and the
 *               per-channel checklist, with inline retry on a failed channel.
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.redesign.ProvisioningChecklist;
import org.iiab.controller.util.ByteFormatter;

import java.util.List;

/**
 * The Kolibri stream's detail view.
 *
 * <p>Observe-only: {@code SetupProgressActivity} owns starting and finishing the
 * session, this only renders it. There is no live variant yet — the picker that
 * starts a session from inside the wizard is a later change.
 *
 * <p>Unlike {@code ZimPreparingFragment} it does not register a listener in
 * {@code onResume} and clear it in {@code onDestroyView}. It observes
 * {@link KolibriSeedRepository} with the fragment's own view lifecycle, so the
 * observer detaches itself and cannot clobber the index's — which is the bug
 * {@code SetupProgressActivity#backToIndex} has to work around for the other
 * streams. See ADR-4954 D7.
 */
public final class KolibriSeedingFragment extends Fragment {

    private TextView percentView;
    private TextView detailView;
    private ProgressBar bar;
    private LinearLayout list;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_k2go_kolibri_seeding, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        percentView = v.findViewById(R.id.k2go_kseed_pct);
        detailView = v.findViewById(R.id.k2go_kseed_detail);
        bar = v.findViewById(R.id.k2go_kseed_bar);
        list = v.findViewById(R.id.k2go_kseed_list);

        KolibriSeedRepository.get().state().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(KolibriSeedState s) {
        if (bar == null || s == null) {
            return;
        }
        int pct = s.overallPercent();
        bar.setProgress(Math.max(0, pct));
        percentView.setText(getString(R.string.k2go_zim_downloading_fmt, pct + "%"));

        if (s.isComplete()) {
            detailView.setText(R.string.k2go_zim_all_ready);
        } else {
            detailView.setText(detailLine(s));
        }

        List<KolibriSeedState.Item> items = s.items();
        ProvisioningChecklist.render(requireContext(), list, items.size(), s.statusOrdinals(),
                KolibriSeedState.Status.DONE.ordinal(),
                KolibriSeedState.Status.FAILED.ordinal(),
                new ProvisioningChecklist.RowText() {
                    @Override
                    public String main(int index) {
                        return items.get(index).label();
                    }

                    @Override
                    public String sub(int index) {
                        KolibriSeedState.Item it = items.get(index);
                        if (it.status() == KolibriSeedState.Status.FAILED) {
                            return getString(R.string.k2go_zim_item_failed_suffix).trim();
                        }
                        return it.bytes() > 0 ? ByteFormatter.toHuman(it.bytes()) : null;
                    }
                },
                index -> KolibriSeedService.retry(requireContext(), index));
    }

    /**
     * "4.2 GB of 61 GB · 2 of 5 items · resumes if it drops", reusing the ZIM
     * format so the two streams read identically when both are on screen.
     */
    private String detailLine(KolibriSeedState s) {
        long total = 0L;
        long done = 0L;
        int terminal = 0;
        for (KolibriSeedState.Item i : s.items()) {
            total += i.bytes();
            if (i.isTerminal()) {
                done += i.bytes();
                terminal++;
            }
        }
        String speed = s.speedBytesPerSec() > 0
                ? "  " + ByteFormatter.toHuman(s.speedBytesPerSec())
                + getString(R.string.k2go_rate_per_second)
                : "";
        return getString(R.string.k2go_zim_prep_detail_fmt,
                ByteFormatter.toHuman(done), ByteFormatter.toHuman(total), speed,
                terminal, s.size());
    }
}
