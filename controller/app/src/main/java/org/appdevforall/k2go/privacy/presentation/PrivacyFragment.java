/*
 * ============================================================================
 * Name        : PrivacyFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. Settings -> Privacy: one switch per box service that can contact an
 *               outside server on its own. Observes PrivacyViewModel; reuses the settings
 *               sub-screen chrome (header + back + scroll list).
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.presentation;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.privacy.domain.OutboundSwitch;
import org.appdevforall.k2go.privacy.domain.SetOutboundUseCase;
import org.appdevforall.k2go.ui.K2GoHeaderView;
import org.appdevforall.k2go.ui.K2GoText;
import org.appdevforall.k2go.util.BusyMessage;
import org.appdevforall.k2go.util.Snackbars;

public class PrivacyFragment extends Fragment {

    private PrivacyViewModel vm;
    private LinearLayout list;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_settings_sub, c, false);
        ((K2GoHeaderView) root.findViewById(R.id.k2go_sub_header)).setTitle(getString(R.string.k2go_settings_privacy));
        list = root.findViewById(R.id.k2go_sub_list);

        vm = new ViewModelProvider(this, new PrivacyViewModelFactory(requireContext())).get(PrivacyViewModel.class);
        vm.state().observe(getViewLifecycleOwner(), this::render);
        vm.notice().observe(getViewLifecycleOwner(), n -> {
            if (n == null || !n.take()) return;
            int text = noticeText(n.result);
            if (text != 0) Snackbars.make(root, getString(text)).show();
        });
        vm.load();
        return root;
    }

    private void render(PrivacyUiState st) {
        if (list == null || st == null) return;
        Context ctx = requireContext();
        list.removeAllViews();
        list.addView(K2GoText.body(ctx, getString(R.string.k2go_privacy_intro), R.color.k2go_muted));

        switch (st.status) {
            case LOADING:
                list.addView(spinner(ctx));
                return;
            case UNSUPPORTED:
                list.addView(problem(ctx, R.string.k2go_privacy_unsupported));
                return;
            case UNREACHABLE:
            case FAILED:
                list.addView(problem(ctx, R.string.k2go_privacy_unreachable));
                return;
            case READY:
            default:
                if (st.switches.isEmpty()) {
                    TextView none = K2GoText.body(ctx, getString(R.string.k2go_privacy_none), R.color.k2go_ink);
                    none.setPadding(0, dp(ctx, 16), 0, 0);
                    list.addView(none);
                    return;
                }
                for (OutboundSwitch sw : st.switches) {
                    list.addView(switchRow(ctx, sw, st.busy()));
                }
        }
    }

    /** BUSY names the operation that holds the box, with the shared refusal text. 0 = no message. */
    private int noticeText(SetOutboundUseCase.Result result) {
        if (result == null) return R.string.k2go_privacy_save_failed;
        switch (result) {
            case BUSY:       return BusyMessage.resFor(requireContext());
            case NEXT_START: return R.string.k2go_privacy_saved_later;
            case UNCHANGED:  return 0;   // the box already had it: no restart, nothing to say
            case RESTARTING:
            default:         return R.string.k2go_privacy_saved;
        }
    }

    private View switchRow(Context ctx, OutboundSwitch sw, boolean locked) {
        boolean kolibri = OutboundSwitch.KOLIBRI.equals(sw.service());
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = dp(ctx, 16);
        row.setPadding(p16, p16, p16, p16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.rightMargin = dp(ctx, 12);
        row.addView(col, clp);
        col.addView(K2GoText.body(ctx, getString(kolibri
                ? R.string.k2go_privacy_kolibri_title : R.string.k2go_privacy_forgejo_title), R.color.k2go_ink));
        col.addView(K2GoText.label(ctx, getString(kolibri
                ? R.string.k2go_privacy_kolibri_sub : R.string.k2go_privacy_forgejo_sub), R.color.k2go_muted));

        MaterialSwitch toggle = new MaterialSwitch(ctx);
        toggle.setMinimumHeight(0);
        toggle.setChecked(sw.outbound());
        toggle.setEnabled(!locked);
        // A click, not a checked-change: only the user's tap may send a change, never a state that
        // Android or a re-render sets. Posted: the new state rebuilds the list, so let the click finish.
        toggle.setOnClickListener(v -> {
            final boolean checked = toggle.isChecked();
            v.post(() -> vm.setOutbound(sw.service(), checked));
        });
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View spinner(Context ctx) {
        CircularProgressIndicator p = new CircularProgressIndicator(ctx);
        p.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(ctx, 24);
        p.setLayoutParams(lp);
        return p;
    }

    private View problem(Context ctx, int msgRes) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = dp(ctx, 16);
        box.setPadding(p16, p16, p16, p16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 16);
        box.setLayoutParams(lp);
        box.addView(K2GoText.body(ctx, getString(msgRes), R.color.k2go_ink));

        MaterialButton retry = new MaterialButton(
                new android.view.ContextThemeWrapper(ctx, R.style.ThemeOverlay_K2Go_Button_Outlined), null);
        retry.setText(getString(R.string.k2go_home_retry));
        retry.setOnClickListener(v -> vm.load());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(ctx, 12);
        box.addView(retry, blp);
        return box;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
