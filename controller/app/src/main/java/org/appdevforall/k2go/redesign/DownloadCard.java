/*
 * ============================================================================
 * Name        : DownloadCard.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394, K2GO-593. Binds the shared download card
 *               (view_k2go_download_card) of a module install to
 *               ModuleDownloadRepository: percent, speed, time left,
 *               Pause/Resume, "Reconnecting n of 5", and the hold on mobile data.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.install.domain.DownloadHold;
import org.appdevforall.k2go.install.domain.ModuleDownloadProgress;
import org.appdevforall.k2go.install.presentation.EtaText;
import org.appdevforall.k2go.install.presentation.InstallService;
import org.appdevforall.k2go.install.presentation.ModuleDownload;
import org.appdevforall.k2go.install.presentation.ModuleDownloadRepository;
import org.appdevforall.k2go.networkpolicy.data.OnlineAllowance;
import org.appdevforall.k2go.networkpolicy.data.SessionMeteredConsentStore;

/**
 * The download half of {@link ModuleProgressPanel}. It only renders {@link ModuleDownloadRepository} for
 * one module and sends the user's taps to {@link InstallService}; it keeps no download state of its own.
 */
final class DownloadCard {

    private final Context ctx;
    private final String module;
    private final View card;
    private final LinearProgressIndicator bar;
    private final TextView pct, rate, note;
    private final Button pause, allow;

    /**
     * @param root     a view that contains view_k2go_download_card
     * @param module   the module key whose download this card shows
     * @param titleRes the card title
     */
    DownloadCard(@NonNull View root, @NonNull String module, @StringRes int titleRes) {
        this.ctx = root.getContext();
        this.module = module;
        card = root.findViewById(R.id.k2go_dl_card);
        ((TextView) root.findViewById(R.id.k2go_dl_title)).setText(titleRes);
        bar = root.findViewById(R.id.k2go_dl_bar);
        pct = root.findViewById(R.id.k2go_dl_pct);
        rate = root.findViewById(R.id.k2go_dl_rate);
        note = root.findViewById(R.id.k2go_dl_note);
        pause = root.findViewById(R.id.k2go_dl_pause);
        allow = root.findViewById(R.id.k2go_dl_allow);
        pause.setOnClickListener(v -> send(isStopped(current())
                ? InstallService.ACTION_RESUME : InstallService.ACTION_PAUSE));
        allow.setOnClickListener(v -> allowNetwork());
    }

    /** Observe the download for the life of {@code owner}. */
    void observe(@NonNull LifecycleOwner owner) {
        ModuleDownloadRepository.get().state().observe(owner, this::render);
    }

    private ModuleDownloadProgress current() {
        return ModuleDownloadRepository.get().current();
    }

    private static boolean isStopped(ModuleDownloadProgress p) {
        return p.isPaused() || p.isHeld();
    }

    private void render(ModuleDownloadProgress p) {
        boolean shown = p != null && p.isRunning() && p.isFor(module);
        card.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) return;

        note.setVisibility(p.isHeld() ? View.VISIBLE : View.GONE);
        allow.setVisibility(p.isHeld() ? View.VISIBLE : View.GONE);
        pause.setVisibility(p.isHeld() ? View.GONE : View.VISIBLE);
        if (p.isReconnecting()) {
            // The box reconnects (aria2 --continue keeps the partial); the percent comes back after.
            bar.setIndeterminate(true);
            pct.setText("");
            rate.setText(ctx.getString(R.string.k2go_dl_attempt, p.reconnectAttempt, p.reconnectTotal));
            pause.setText(R.string.k2go_dl_pause);
            return;
        }
        bar.setIndeterminate(p.percent < 0);
        if (p.percent >= 0) bar.setProgressCompat(p.percent, true);
        pct.setText(p.percent >= 0 ? ctx.getString(R.string.dash_format_pct, p.percent) : "");
        if (p.isHeld()) {
            boolean metered = p.holdReason == DownloadHold.Reason.METERED_NO_CONSENT;
            rate.setText(R.string.k2go_mod_phase_queued);
            note.setText(metered ? R.string.k2go_dl_held_metered : R.string.k2go_dl_held_offline);
            allow.setText(metered ? R.string.k2go_netpolicy_continue : R.string.k2go_offline_action_allow_once);
            return;
        }
        if (p.isPaused()) {
            rate.setText(R.string.k2go_dl_paused);
            pause.setText(R.string.k2go_dl_resume);
            return;
        }
        pause.setText(R.string.k2go_dl_pause);
        if (p.speed.isEmpty()) {
            rate.setText("");
        } else if (p.etaSeconds >= 0) {
            rate.setText(ctx.getString(R.string.k2go_dl_speed_eta, p.speed, EtaText.of(ctx, p.etaSeconds)));
        } else {
            rate.setText(ctx.getString(R.string.k2go_dl_speed, p.speed));
        }
    }

    /**
     * The tap is the user's consent, for this session only, the same as the start gate gives: mobile
     * data (SessionMeteredConsentStore) or, with Keep offline, this kind of install
     * (OnlineAllowance). Then the service continues; if a second reason remains, the card shows it.
     */
    private void allowNetwork() {
        ModuleDownloadProgress p = current();
        if (!p.isHeld()) return;
        if (p.holdReason == DownloadHold.Reason.METERED_NO_CONSENT) {
            SessionMeteredConsentStore.get().grant();
        } else {
            OnlineAllowance.allow(ModuleDownload.allowanceKind(module));
        }
        send(InstallService.ACTION_RESUME);
    }

    private void send(String action) {
        Intent i = new Intent(ctx, InstallService.class);
        i.setAction(action);
        ctx.startService(i);
    }
}
