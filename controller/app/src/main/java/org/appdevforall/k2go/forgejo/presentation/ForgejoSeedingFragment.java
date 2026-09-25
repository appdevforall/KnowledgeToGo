/*
 * ============================================================================
 * Name        : ForgejoSeedingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-423. Detail card for the Forgejo seed, hosted inside the
 *               Finishing-setup index. Observe-only: it never starts or stops the
 *               seed (ForgejoSeedService owns that). It mirrors ForgejoSeedRepository
 *               into the phase line, the "repositories added" count and the terminal
 *               log. The index host provides Back / Run in background.
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.presentation;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.widget.LiveLogPanel;

import java.util.List;

/**
 * The seed reports a coarse state plus a streamed log, not per-item bytes, so this card follows
 * {@link org.appdevforall.k2go.redesign.MapsPreparingFragment}: an animation, the latest line as
 * the phase, and a collapsible terminal. {@link ForgejoSeedRepository} is a plain holder (not
 * LiveData), so the card polls it once a second while resumed.
 */
public class ForgejoSeedingFragment extends Fragment {

    private static final long TICK_MS = 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView reposLine;
    private LiveLogPanel logPanel;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            render();
            main.postDelayed(this, TICK_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_forgejo_seeding, container, false);

        status = root.findViewById(R.id.k2go_forgejo_seed_status);
        reposLine = root.findViewById(R.id.k2go_forgejo_seed_repos);
        logPanel = root.findViewById(R.id.k2go_forgejo_seed_log_panel);
        logPanel.setOnExpandListener(this::renderLog);

        // The download art (the seed pulls the repos over the network). Set here, not in the layout,
        // so the animation asset lives in one place (ProgressVisuals' rule).
        com.airbnb.lottie.LottieAnimationView anim = root.findViewById(R.id.k2go_working_anim);
        if (anim != null) {
            anim.setAnimation(org.appdevforall.k2go.util.ProgressVisuals.rawResForKey("forgejo"));
            anim.playAnimation();
        }

        render();
        return root;
    }

    @Override public void onResume()  { super.onResume(); main.post(tick); }
    @Override public void onPause()   { main.removeCallbacks(tick); super.onPause(); }

    private void render() {
        ForgejoSeedRepository repo = ForgejoSeedRepository.get();

        if (repo.isFailed()) {
            status.setText(R.string.k2go_forgejo_seed_state_failed);
        } else if (repo.isComplete()) {
            status.setText(R.string.k2go_forgejo_seed_state_done);
        } else {
            String last = repo.lastLine();
            status.setText(last.isEmpty() ? getString(R.string.k2go_forgejo_seed_phase_start) : last);
        }

        // "repositories added" only when the repos were opted in; the admin+org path shows nothing.
        int seeded = repo.reposSeeded();
        if (repo.includeRepos() && seeded > 0) {
            reposLine.setVisibility(View.VISIBLE);
            reposLine.setText(getString(R.string.k2go_forgejo_seed_repos_fmt, seeded));
        } else {
            reposLine.setVisibility(View.GONE);
        }

        if (logPanel.isExpanded()) renderLog();
    }

    private void renderLog() {
        List<String> lines = ForgejoSeedRepository.get().logLines();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        logPanel.setContent(sb.toString());
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacks(tick);
        super.onDestroyView();
    }
}
