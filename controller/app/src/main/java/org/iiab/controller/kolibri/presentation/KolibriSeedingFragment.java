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
 * session, this only renders it. That is now true of every way in — ADFA-5074
 * removed the Get More door that hosted this same fragment inside {@code
 * SetupLibraryActivity}, where no chrome existed and this class had to grow its
 * own footer to give the user a way out. The host owns the exit.
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
        org.iiab.controller.util.ProgressVisuals.apply(
                v, org.iiab.controller.system.domain.ContentType.COURSES);   // ADFA-5074

        KolibriSeedRepository.get().state().observe(getViewLifecycleOwner(), this::render);
    }

    // ADFA-5074: a pinned footer used to live here, shown only when the host was
    // SetupLibraryActivity. Its Done button called KolibriSeedService.finishSession() and
    // navigated to the Library, and it withheld that exit when a channel had failed so the
    // retry rows stayed reachable.
    //
    // Both are now the host's, and the host does more of it: SetupProgressActivity.goHome
    // finishes all three content sessions rather than only this one, offers the exit as
    // Finish with an explanatory note when something failed instead of withholding it, and
    // redirects on its own when everything succeeded. That last one is the real gain —
    // this screen made the user press a button to leave a run that had finished cleanly.
    //
    // The stricter "no exit while something failed" rule was a local invention. It is not
    // kept: the index answers for four streams at once, and it cannot withhold the exit for
    // one of them without stranding the other three.

    private void render(KolibriSeedState s) {
        if (bar == null || s == null) {
            return;
        }
        int pct = s.overallPercent();
        bar.setProgress(Math.max(0, pct));
        percentView.setText(getString(R.string.k2go_zim_downloading_fmt, pct + "%"));

        // "Complete" means every item is terminal, and terminal includes FAILED — so
        // it is not the same as "everything arrived". Saying "all content ready" over
        // a run with failures is a lie the host would then act on: the index reads the
        // same failure count to decide between redirecting home and offering Finish.
        boolean settled = s.isComplete();
        int failed = s.failedCount();
        if (settled && failed == 0) {
            detailView.setText(R.string.k2go_zim_all_ready);
        } else if (settled) {
            detailView.setText(getString(R.string.k2go_kolibri_failed_fmt, failed));
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
     *
     * <p>The byte figures come from the state, not from a second sum computed
     * here: the caption and the progress bar have to agree, and they did not when
     * each derived its own total.
     */
    private String detailLine(KolibriSeedState s) {
        String speed = s.speedBytesPerSec() > 0
                ? "  " + ByteFormatter.toHuman(s.speedBytesPerSec())
                + getString(R.string.k2go_rate_per_second)
                : "";
        return getString(R.string.k2go_zim_prep_detail_fmt,
                ByteFormatter.toHuman(s.transferredBytes()),
                ByteFormatter.toHuman(s.totalBytes()),
                speed, s.terminalCount(), s.size());
    }
}
