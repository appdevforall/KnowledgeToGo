/*
 * ============================================================================
 * Name        : PendingOrdersFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The "Pending downloads" screen (ADFA-5169, finding 6). Lists each
 *               queued content order (ZIM / Books / Courses) by item with its own
 *               Cancel; shows an empty state; and, when a download is running, a
 *               link to the live index. Observes PendingOrdersViewModel — it does
 *               not read or format the wishlists itself. Reuses the settings
 *               sub-screen chrome (title + back + scroll list).
 * ============================================================================
 */
package org.iiab.controller.pending.presentation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.R;
import org.iiab.controller.pending.domain.PendingOrder;
import org.iiab.controller.redesign.SetupProgressActivity;
import org.iiab.controller.util.ByteFormatter;

public class PendingOrdersFragment extends Fragment {

    private PendingOrdersViewModel vm;
    private LinearLayout list;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_settings_sub, c, false);
        ((TextView) root.findViewById(R.id.k2go_sub_title)).setText(getString(R.string.k2go_pending_title));
        root.findViewById(R.id.k2go_sub_back).setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        list = root.findViewById(R.id.k2go_sub_list);

        vm = new ViewModelProvider(this, new PendingOrdersViewModelFactory(requireContext()))
                .get(PendingOrdersViewModel.class);
        vm.state().observe(getViewLifecycleOwner(), this::render);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (vm != null) vm.refresh();   // a cancel elsewhere, or a drain, may have changed the list
    }

    private void render(PendingOrdersUiState st) {
        if (list == null || st == null || st.loading) return;
        Context ctx = requireContext();
        list.removeAllViews();

        if (st.somethingRunning) {
            list.addView(runningBanner(ctx));
        }
        if (st.isEmpty()) {
            list.addView(emptyText(ctx));
            return;
        }
        for (PendingOrder order : st.orders) {
            list.addView(orderRow(ctx, order));
        }
    }

    private TextView emptyText(Context ctx) {
        TextView t = new TextView(ctx);
        t.setText(getString(R.string.k2go_pending_empty));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(ctx, 24);
        t.setPadding(pad, pad, pad, pad);
        return t;
    }

    private View runningBanner(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(ctx, 16), padV = dp(ctx, 12);
        row.setPadding(padH, padV, padH, padV);

        TextView label = new TextView(ctx);
        label.setText(getString(R.string.k2go_pending_running));
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        label.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        TextView link = new TextView(ctx);
        link.setText(getString(R.string.k2go_pending_see_progress));
        link.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        link.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
        link.setOnClickListener(v -> startActivity(new Intent(ctx, SetupProgressActivity.class)));
        row.addView(link);
        return row;
    }

    private View orderRow(Context ctx, PendingOrder order) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(ctx, 16), padV = dp(ctx, 12);
        row.setPadding(padH, padV, padH, padV);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setText(order.name() == null ? order.id() : order.name());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        name.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
        textCol.addView(name);

        if (order.bytes() > 0L) {
            TextView size = new TextView(ctx);
            size.setText(ByteFormatter.toHuman(order.bytes()));
            size.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            size.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
            textCol.addView(size);
        }
        row.addView(textCol);

        // ADFA-5027 homologation: the official M3 delete (trash) icon in the danger colour, as the
        // maps (FqrController) and Kiwix (KiwixManageController) per-row deletes use — not a "✕" glyph.
        ImageView cancel = new ImageView(ctx);
        cancel.setImageResource(R.drawable.ic_delete_24);
        cancel.setColorFilter(ContextCompat.getColor(ctx, R.color.k2go_clay));
        cancel.setContentDescription(getString(R.string.k2go_cancel));
        int pad = dp(ctx, 8), tap = dp(ctx, 44);
        cancel.setPadding(pad, pad, pad, pad);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(tap, tap));
        cancel.setOnClickListener(v -> vm.cancel(order));
        row.addView(cancel);
        return row;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
