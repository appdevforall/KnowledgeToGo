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
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.auth.data.FactoryReminderStore;
import org.appdevforall.k2go.auth.data.PasswordRepositoryImpl;
import org.appdevforall.k2go.auth.domain.AuthNotice;
import org.appdevforall.k2go.auth.domain.PasswordRepository;
import org.appdevforall.k2go.auth.domain.PasswordState;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

import java.util.ArrayList;
import java.util.List;

public final class AuthNoticeController {
    private AuthNoticeController() {}

    private static final String[] SERVICES = {"calibre", "kolibri", "forgejo"};

    /** Product names: proper names, the same in every language. */
    private static String productName(String service) {
        switch (service) {
            case "calibre": return "Calibre-Web";
            case "kolibri": return "Kolibri";
            default: return "Forgejo";
        }
    }

    // The library opens before its services finish starting (Kolibri is the slowest). A service
    // that is not running answers "unreachable", so ask it again until all answer or the tries run
    // out; a service that is not installed only delays the notice. A service that answered is not
    // asked again: each check is a sign-in, and a failed one counts toward Calibre-Web's lock
    // (3 a minute, 40 a day).
    private static final int TRIES = 8;
    private static final long GAP_MS = 15_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Checks the three services and shows at most one notice. */
    public static void check(@NonNull Activity act, @NonNull Runnable openAuthentication) {
        attempt(act, new PasswordRepositoryImpl(), openAuthentication,
                new PasswordState[SERVICES.length], 1);   // fixed order in the notice
    }

    private static boolean answered(PasswordState s) {
        return s != null && s.sync != PasswordState.Sync.UNREACHABLE;
    }

    private static void attempt(Activity act, PasswordRepository repo, Runnable openAuthentication,
                                PasswordState[] states, int tryNo) {
        final int[] pending = {0};
        for (PasswordState s : states) if (!answered(s)) pending[0]++;
        for (int i = 0; i < SERVICES.length; i++) {
            if (answered(states[i])) continue;
            final int slot = i;
            repo.status(SERVICES[i], new PasswordRepository.Callback<PasswordState>() {
                @Override public void onResult(PasswordState s) { states[slot] = s; next(); }
                @Override public void onFailure(PasswordRepository.Failure f) { next(); }
                private void next() {   // callbacks run on the main thread, so the count needs no lock
                    if (--pending[0] > 0) return;
                    if (act.isFinishing() || act.isDestroyed()) return;
                    List<PasswordState> known = new ArrayList<>();
                    boolean allAnswered = true;
                    for (PasswordState st : states) {
                        if (!answered(st)) allAnswered = false;
                        if (st != null) known.add(st);
                    }
                    if (!allAnswered && tryNo < TRIES) {
                        MAIN.postDelayed(() -> {
                            if (!act.isFinishing() && !act.isDestroyed()) {
                                attempt(act, repo, openAuthentication, states, tryNo + 1);
                            }
                        }, GAP_MS);
                        return;
                    }
                    show(act, AuthNotice.decide(known, new FactoryReminderStore(act).isOff()), openAuthentication);
                }
            });
        }
    }

    private static void show(Activity act, AuthNotice notice, Runnable openAuthentication) {
        if (notice.kind == AuthNotice.Kind.NONE) return;
        List<String> names = new ArrayList<>();
        for (String s : notice.services) names.add(productName(s));
        String list = TextUtils.join(", ", names);
        ContextThemeWrapper ctx = new ContextThemeWrapper(act, R.style.Theme_K2Go);
        if (notice.kind == AuthNotice.Kind.MISMATCH) {
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
                .setMessage(act.getString(R.string.k2go_auth_notice_factory_msg, list))
                .setPositive(R.string.k2go_auth_notice_open, openAuthentication::run)
                .setNeutral(R.string.k2go_auth_notice_never, () -> confirmStop(ctx, () -> { }, () -> { }))
                .setNegative(R.string.k2go_auth_notice_close, null)
                .show();
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
