/*
 * ============================================================================
 * Name        : AuthNoticeController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. The start-up notice about the box admin passwords: a service that rejects
 *               the stored password (auto-login fails), or services that still take the public
 *               factory password. The rule lives in AuthNotice; the state comes live from the box.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.presentation;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.auth.data.FactoryReminderStore;
import org.appdevforall.k2go.auth.data.PasswordRepositoryImpl;
import org.appdevforall.k2go.auth.domain.AuthNotice;
import org.appdevforall.k2go.auth.domain.AuthServices;
import org.appdevforall.k2go.auth.domain.PasswordRepository;
import org.appdevforall.k2go.auth.domain.PasswordState;
import org.appdevforall.k2go.system.data.PlatformEvidence;
import org.appdevforall.k2go.system.domain.PlatformPresence;
import org.appdevforall.k2go.ui.K2GoText;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

import java.util.ArrayList;
import java.util.List;

public final class AuthNoticeController {
    private AuthNoticeController() {}


    /**
     * Checks the services and shows at most one notice. Call it when Home turns "Ready"
     * (LibraryActivity.onLibraryReady): the services answered their Home probes by then, so one
     * status call each is enough. A service that a probe found absent (the "Not installed" card,
     * PlatformEvidence) is not asked. No retries: each status call is a sign-in, and a failed one
     * counts toward Calibre-Web's lock (3 a minute, 40 a day).
     */
    public static void check(@NonNull Activity act, @NonNull Runnable openAuthentication) {
        final List<String> asked = new ArrayList<>();
        for (String s : AuthServices.ALL) {
            if (PlatformEvidence.last(AuthServices.homeEndpoint(s)) != PlatformPresence.Evidence.ABSENT) asked.add(s);
        }
        if (asked.isEmpty()) return;
        final PasswordRepository repo = new PasswordRepositoryImpl();
        final PasswordState[] states = new PasswordState[asked.size()];   // fixed order in the notice
        final int[] pending = {asked.size()};
        for (int i = 0; i < asked.size(); i++) {
            final int slot = i;
            repo.status(asked.get(i), new PasswordRepository.Callback<PasswordState>() {
                @Override public void onResult(PasswordState s) { states[slot] = s; next(); }
                @Override public void onFailure(PasswordRepository.Failure f) { next(); }
                private void next() {   // callbacks run on the main thread, so the count needs no lock
                    if (--pending[0] > 0) return;
                    if (act.isFinishing() || act.isDestroyed()) return;
                    List<PasswordState> known = new ArrayList<>();
                    for (PasswordState st : states) if (st != null) known.add(st);
                    show(act, AuthNotice.decide(known, new FactoryReminderStore(act).isOff()), openAuthentication);
                }
            });
        }
    }

    private static void show(Activity act, AuthNotice notice, Runnable openAuthentication) {
        if (notice.kind == AuthNotice.Kind.NONE) return;
        ContextThemeWrapper ctx = new ContextThemeWrapper(act, R.style.Theme_K2Go);
        if (notice.kind == AuthNotice.Kind.MISMATCH) {
            List<String> names = new ArrayList<>();
            for (String s : notice.services) names.add(AuthServices.productName(s));
            String list = TextUtils.join(", ", names);
            new BrandDialog(ctx)
                    .setTitle(R.string.k2go_auth_notice_mismatch_title)
                    .setMessage(act.getString(R.string.k2go_auth_notice_mismatch_msg, list))
                    .setPositive(R.string.k2go_auth_notice_open, openAuthentication::run)
                    .setNegative(R.string.k2go_auth_notice_close, null)
                    .show();
            return;
        }
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_auth_notice_factory_title)
                .setMessage(R.string.k2go_auth_notice_factory_lead)
                .setContentView(serviceList(ctx, notice.services, ctx.getString(R.string.k2go_auth_notice_factory_risk)))
                .setPositive(R.string.k2go_auth_notice_open, openAuthentication::run)
                .setNeutral(R.string.k2go_auth_notice_never, () -> confirmStop(ctx, () -> { }, () -> { }))
                .setNeutralColor(R.color.k2go_warn_ink)   // allowed, but not advised
                .setNegative(R.string.k2go_auth_notice_close, null)
                .show();
    }

    /**
     * The services as bullet items in two columns, each one its screen name over the product name in
     * parentheses ("Read a book" / "(Calibre-Web)"), then the risk line. Two columns keep a long list
     * short: the rows grow as N / 2.
     */
    private static View serviceList(Context ctx, List<String> services, String risk) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int gap = Math.round(8 * ctx.getResources().getDisplayMetrics().density);
        for (int i = 0; i < services.size(); i += 2) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2; j++) {
                View cell = j < services.size() ? serviceItem(ctx, services.get(j)) : new Space(ctx);   // Space measures 0 high; a View takes all the height it is offered
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j > i) lp.setMarginStart(gap);
                row.addView(cell, lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rlp.topMargin = gap;
            column.addView(row, rlp);
        }
        TextView riskView = K2GoText.label(ctx, risk, R.color.dash_text_secondary);   // as the dialog message
        riskView.setLineSpacing(4 * ctx.getResources().getDisplayMetrics().density, 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * ctx.getResources().getDisplayMetrics().density);
        column.addView(riskView, lp);
        return column;
    }

    private static View serviceItem(Context ctx, String service) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.addView(K2GoText.label(ctx, "•  ", R.color.k2go_ink));   // bullet
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = K2GoText.label(ctx, ctx.getString(AuthScreenNames.of(service)), R.color.k2go_ink);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        text.addView(name);
        text.addView(K2GoText.label(ctx, "(" + AuthServices.productName(service) + ")", R.color.k2go_muted));
        item.addView(text);
        return item;
    }

    /** Turning the reminder off always asks first, from the notice or from the Settings switch: the
     *  record that the risk was shown is the time of this choice. */
    public static void confirmStop(@NonNull Context ctx, @NonNull Runnable onStopped, @NonNull Runnable onKept) {
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_auth_notice_never_title)
                .setMessage(R.string.k2go_auth_notice_never_msg)
                .setDestructive(R.string.k2go_auth_notice_never_confirm, () -> {
                    new FactoryReminderStore(ctx).turnOff(System.currentTimeMillis());
                    onStopped.run();
                })
                .setNegative(R.string.k2go_auth_notice_never_keep, onKept::run)
                .setOnCancel(onKept::run)
                .show();
    }
}
