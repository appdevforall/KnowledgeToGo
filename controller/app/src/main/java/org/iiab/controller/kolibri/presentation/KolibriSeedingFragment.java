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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.redesign.LibraryActivity;
import org.iiab.controller.redesign.ProvisioningChecklist;
import org.iiab.controller.redesign.SetupLibraryActivity;
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
    private LinearLayout footer;
    private TextView footerNote;
    private Button done;

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
        org.iiab.controller.redesign.ProgressVisuals.apply(
                v, org.iiab.controller.system.domain.ContentType.COURSES);   // ADFA-5074
        footer = v.findViewById(R.id.k2go_kseed_footer);
        footerNote = v.findViewById(R.id.k2go_kseed_footer_note);
        done = v.findViewById(R.id.k2go_kseed_done);
        done.setOnClickListener(x -> finishAndGoToLibrary());

        KolibriSeedRepository.get().state().observe(getViewLifecycleOwner(), this::render);
    }

    /**
     * The pinned footer: shown only on the live door, and only once the run is
     * settled.
     *
     * <p>With failures the exit is withheld rather than relabelled. Leaving would
     * clear the session, and the session is what holds the failed rows and their
     * inline retry — the only way to recover them. So the screen says what failed
     * and keeps the retries reachable; the user leaves by going back, which loses
     * nothing.
     */
    private void renderFooter(boolean settled, int failed) {
        boolean offerExit = settled && failed == 0 && isLiveDoor();
        footer.setVisibility(settled && isLiveDoor() ? View.VISIBLE : View.GONE);
        done.setVisibility(offerExit ? View.VISIBLE : View.GONE);
        if (settled && failed > 0 && isLiveDoor()) {
            footerNote.setVisibility(View.VISIBLE);
            footerNote.setText(R.string.k2go_kolibri_retry_before_leaving);
        } else {
            footerNote.setVisibility(View.GONE);
        }
    }

    /**
     * The end of the live flow: clear the session and land in the library.
     *
     * <p>Both halves matter. Without the navigation the screen simply stops, with
     * nothing to press. Without {@code finishSession()} the finished session stays
     * on the repository, {@code hasSession()} keeps reporting true, and every later
     * download is refused with "another download is running" — a dead end that
     * outlives the screen. The post-install host does this in its own Finish; on
     * this door nobody was doing it.
     */
    private void finishAndGoToLibrary() {
        KolibriSeedService.finishSession();
        if (getActivity() == null) {
            return;
        }
        // CLEAR_TOP + SINGLE_TOP lands on the Library already below in the stack and
        // drops the screens above it, this one included — the same navigation
        // SetupProgressActivity.goHome uses, and for the same reason.
        startActivity(new android.content.Intent(getActivity(), LibraryActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_library));
        // Finished as well as cleared, exactly as goHome does: CLEAR_TOP only drops
        // us when the Library is already below in the stack, and it is not always.
        getActivity().finish();
    }

    /**
     * True when this screen is the whole flow rather than a detail inside the
     * post-install index — which is the case reached from Get More, where no host
     * chrome exists to offer a way out.
     */
    private boolean isLiveDoor() {
        return getActivity() instanceof SetupLibraryActivity;
    }

    private void render(KolibriSeedState s) {
        if (bar == null || s == null) {
            return;
        }
        int pct = s.overallPercent();
        bar.setProgress(Math.max(0, pct));
        percentView.setText(getString(R.string.k2go_zim_downloading_fmt, pct + "%"));

        // "Complete" means every item is terminal, and terminal includes FAILED — so
        // it is not the same as "everything arrived". Saying "all content ready" over
        // a run with failures is the lie that the exit button below would then act on.
        boolean settled = s.isComplete();
        int failed = s.failedCount();
        if (settled && failed == 0) {
            detailView.setText(R.string.k2go_zim_all_ready);
        } else if (settled) {
            detailView.setText(getString(R.string.k2go_kolibri_failed_fmt, failed));
        } else {
            detailView.setText(detailLine(s));
        }
        renderFooter(settled, failed);

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
