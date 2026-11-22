/*
 * ============================================================================
 * Name        : ServicePasswordSection.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. Settings > Authentication > <service>: the live state, "Change password",
 *               "Repair" (only while the service rejects the stored password) and "Reset to the
 *               factory password". The box does the work (dash-node sockets/passwords.ts); this view
 *               only renders ServicePasswordViewModel.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.presentation;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.auth.data.CredentialsClient;
import org.appdevforall.k2go.auth.domain.PasswordState;
import org.appdevforall.k2go.ui.K2GoInputs;
import org.appdevforall.k2go.ui.K2GoText;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

public final class ServicePasswordSection {
    private ServicePasswordSection() {}

    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    private static LinearLayout.LayoutParams top(Context c, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, marginDp);
        return lp;
    }

    private static MaterialButton filledButton(Context ctx, int textRes) {
        MaterialButton b = new MaterialButton(
                new android.view.ContextThemeWrapper(ctx, R.style.ThemeOverlay_K2Go_Button_Filled), null);
        b.setText(ctx.getString(textRes));
        return b;
    }

    private static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.5f);
    }

    public static void build(@NonNull Fragment host, @NonNull LinearLayout list, @NonNull String service) {
        final Context ctx = host.requireContext();
        final ServicePasswordViewModel vm = new ViewModelProvider(host,
                new ServicePasswordViewModel.Factory(service))
                .get("password:" + service, ServicePasswordViewModel.class);

        final TextView user = K2GoText.body(ctx, "", R.color.k2go_ink);
        list.addView(user, top(ctx, 16));
        final TextView state = K2GoText.body(ctx, ctx.getString(R.string.k2go_auth_pw_checking), R.color.k2go_muted);
        list.addView(state, top(ctx, 8));

        // Repair: only while the service rejects the stored password; then it is the only way forward,
        // so it comes before "Change password" (which needs a working stored password).
        final LinearLayout repair = new LinearLayout(ctx);
        repair.setOrientation(LinearLayout.VERTICAL);
        repair.setVisibility(View.GONE);
        list.addView(repair, top(ctx, 24));
        repair.addView(K2GoText.sectionLabel(ctx, ctx.getString(R.string.k2go_auth_pw_repair_title)));
        repair.addView(K2GoText.label(ctx, ctx.getString(R.string.k2go_auth_pw_repair_hint), R.color.k2go_muted), top(ctx, 8));
        repair.addView(K2GoInputs.fieldLabel(ctx, ctx.getString(R.string.k2go_auth_password), ""));
        final EditText repairPw = K2GoInputs.passwordInput(ctx);
        repair.addView(repairPw);
        final MaterialButton repairSave = filledButton(ctx, R.string.k2go_auth_save);
        repair.addView(repairSave, top(ctx, 16));

        // Change password
        list.addView(K2GoText.sectionLabel(ctx, ctx.getString(R.string.k2go_auth_pw_change_title)), top(ctx, 24));
        list.addView(K2GoInputs.fieldLabel(ctx, ctx.getString(R.string.k2go_auth_pw_new), ""));
        final EditText newPw = K2GoInputs.passwordInput(ctx);
        list.addView(newPw);
        list.addView(K2GoInputs.fieldLabel(ctx, ctx.getString(R.string.k2go_auth_pw_confirm), ""));
        final EditText confirmPw = K2GoInputs.passwordInput(ctx);
        list.addView(confirmPw);
        list.addView(K2GoText.label(ctx, ctx.getString(R.string.k2go_auth_pw_rule), R.color.k2go_muted), top(ctx, 8));
        final MaterialButton change = filledButton(ctx, R.string.k2go_auth_pw_change_button);
        list.addView(change, top(ctx, 16));

        final TextView result = K2GoText.label(ctx, "", R.color.k2go_muted);
        result.setVisibility(View.GONE);
        list.addView(result, top(ctx, 12));

        final TextView reset = K2GoText.label(ctx, ctx.getString(R.string.k2go_auth_pw_reset), R.color.k2go_teal);
        reset.setClickable(true);
        reset.setGravity(Gravity.CENTER);
        int p12 = dp(ctx, 12);
        reset.setPadding(0, p12, 0, p12);   // 48dp touch target with the label's line height
        list.addView(reset, top(ctx, 16));

        change.setOnClickListener(v -> vm.change(newPw.getText().toString(), confirmPw.getText().toString()));
        repairSave.setOnClickListener(v -> vm.repair(user.getTag() instanceof String ? (String) user.getTag() : "",
                repairPw.getText().toString()));
        reset.setOnClickListener(v -> new BrandDialog(ctx)
                .setTitle(R.string.k2go_auth_pw_reset_title)
                .setMessage(R.string.k2go_auth_pw_reset_msg)
                .setDestructive(R.string.k2go_auth_pw_reset_confirm, vm::reset)
                .setNegative(R.string.k2go_cancel, null)
                .show());

        CredentialsClient.describe(service, new CredentialsClient.DescribeCb() {
            @Override public void onOk(String username, String password, boolean isDefault) {
                if (!host.isAdded()) return;
                user.setText(ctx.getString(R.string.k2go_auth_pw_user, username));
                user.setTag(username);   // the repair keeps the stored username
                ServicePasswordViewModel.UiState now = vm.ui().getValue();
                enable(repairSave, now != null && !now.busy);
            }
            @Override public void onErr() {
                // Repair needs the stored username; without it the button stays off, so say why.
                if (host.isAdded()) user.setText(ctx.getString(R.string.k2go_auth_load_failed));
            }
        });

        vm.ui().observe(host.getViewLifecycleOwner(), ui -> {
            PasswordState s = ui.state;
            if (s != null) renderState(ctx, state, s);
            boolean mismatch = s != null && s.sync == PasswordState.Sync.MISMATCH;
            boolean reachable = s != null && s.sync != PasswordState.Sync.UNREACHABLE;
            // No admin account in the service: nothing to change, repair or reset; all gray.
            boolean noAccount = s != null && s.sync == PasswordState.Sync.NO_ACCOUNT;
            repair.setVisibility(mismatch ? View.VISIBLE : View.GONE);
            enable(change, !ui.busy && reachable && !mismatch && !noAccount);
            enable(newPw, !noAccount);
            enable(confirmPw, !noAccount);
            enable(repairSave, !ui.busy && user.getTag() != null);
            enable(reset, !ui.busy && reachable && !noAccount);
            renderResult(ctx, result, ui);
            if (!ui.busy && (ui.result == ServicePasswordViewModel.Result.CHANGED
                    || ui.result == ServicePasswordViewModel.Result.CHANGED_UNVERIFIED)) {
                newPw.setText("");
                confirmPw.setText("");
            }
            if (!ui.busy && (ui.result == ServicePasswordViewModel.Result.REPAIRED
                    || ui.result == ServicePasswordViewModel.Result.REPAIRED_UNVERIFIED)) repairPw.setText("");
        });
    }

    private static void renderState(Context ctx, TextView v, PasswordState s) {
        int text;
        int color;
        if (s.sync == PasswordState.Sync.MISMATCH) {
            text = R.string.k2go_auth_pw_state_mismatch; color = R.color.k2go_clay;
        } else if (s.sync == PasswordState.Sync.NO_ACCOUNT) {
            text = R.string.k2go_auth_pw_state_no_account; color = R.color.k2go_muted;
        } else if (s.sync == PasswordState.Sync.UNREACHABLE) {
            text = R.string.k2go_auth_pw_state_unreachable; color = R.color.k2go_muted;
        } else if (s.usesFactoryPassword()) {
            text = R.string.k2go_auth_pw_state_factory; color = R.color.k2go_clay;
        } else {
            text = R.string.k2go_auth_pw_state_ok; color = R.color.k2go_leaf;
        }
        v.setText(ctx.getString(text));
        v.setTextColor(ContextCompat.getColor(ctx, color));
    }

    private static void renderResult(Context ctx, TextView v, ServicePasswordViewModel.UiState ui) {
        int text = 0;
        boolean bad = true;
        switch (ui.result) {
            case CHANGED: text = R.string.k2go_auth_pw_changed; bad = false; break;
            case CHANGED_UNVERIFIED: text = R.string.k2go_auth_pw_changed_unverified; bad = false; break;
            case RESET_DONE: text = R.string.k2go_auth_pw_reset_done; bad = false; break;
            case RESET_UNVERIFIED: text = R.string.k2go_auth_pw_changed_unverified; bad = false; break;
            case REPAIRED: text = R.string.k2go_auth_verified; bad = false; break;
            case REPAIRED_UNVERIFIED: text = R.string.k2go_auth_pw_changed_unverified; bad = false; break;
            case NOT_SAME: text = R.string.k2go_auth_pw_not_same; break;
            case WEAK_PASSWORD: text = R.string.k2go_auth_pw_weak; break;
            case MISMATCH: text = R.string.k2go_auth_pw_err_mismatch; break;
            case BUSY: text = R.string.k2go_auth_pw_err_busy; break;
            case UNREACHABLE: text = R.string.k2go_auth_pw_err_unreachable; break;
            case REJECTED: text = R.string.k2go_auth_bad; break;
            case FAILED: text = R.string.k2go_auth_pw_err_failed; break;
            case LOAD_FAILED: text = R.string.k2go_auth_load_failed; break;
            default: break;
        }
        if (ui.busy && ui.state != null) { text = R.string.k2go_auth_pw_working; bad = false; }
        if (text == 0) { v.setVisibility(View.GONE); return; }
        v.setText(ctx.getString(text));
        v.setTextColor(ContextCompat.getColor(ctx, bad ? R.color.k2go_clay : R.color.k2go_teal));
        v.setVisibility(View.VISIBLE);
    }
}
