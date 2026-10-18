/*
 * ============================================================================
 * Name        : K2GoBottomSheet.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-477. The ONE owner of how a Home modal bottom sheet looks and behaves, so the
 *               chrome, the open-expanded behavior and the lifecycle cannot drift or be re-copied
 *               into each new sheet (which is how ModuleActionSheet and the Code on the Go Dev sheet
 *               ended up with the same clipping bug in two places).
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.appdevforall.k2go.R;

/**
 * The shared surface for every Home MODAL bottom sheet (tap -> focused choices over a scrim). It owns
 * three things that must stay identical across sheets, and used to be hand-copied into each one:
 *
 * <ul>
 *   <li><b>Chrome</b>: the surface background, the drag handle, a {@link NestedScrollView} wrapper and
 *       the standard padding.</li>
 *   <li><b>Presentation</b>: a modal sheet opens showing its content ({@code skipCollapsed} +
 *       {@code STATE_EXPANDED}), not at the framework's default collapsed peek : that peek reveals a
 *       short sheet in portrait but clips to the header in landscape and at large font scales. A short
 *       sheet still sits at its content height ({@code fitToContents}), so portrait is unchanged.</li>
 *   <li><b>Lifecycle</b>: tied to the caller's {@link LifecycleOwner}, the dialog auto-dismisses on
 *       {@code ON_STOP} (and the observer removes itself), so a sheet left open across a rotation or a
 *       backgrounding cannot leak its window. These sheets are transient by design : they hold live
 *       callbacks that cannot survive process death, so nothing is persisted or restored; the user
 *       re-opens after a cold start.</li>
 * </ul>
 *
 * A new sheet calls {@link #show} and fills the content column; it cannot reintroduce the clipping or a
 * lifecycle leak, because it does not own either. Grep check: only this class constructs a
 * {@link BottomSheetDialog}.
 */
public final class K2GoBottomSheet {

    private K2GoBottomSheet() {}

    /** Fills the sheet's content column (already carrying the drag handle) with the sheet's own rows. */
    public interface Builder {
        void build(@NonNull BottomSheetDialog dialog, @NonNull LinearLayout content);
    }

    /**
     * Build, wire and show a modal bottom sheet.
     *
     * @param ctx       a themed Context (an Activity)
     * @param owner     the lifecycle the sheet lives under; it auto-dismisses on this owner's stop
     * @param builder   adds the header and rows to the content column (receives the dialog for dismiss)
     * @param onDismiss runs once on teardown (user dismiss OR lifecycle stop), e.g. to drop transient
     *                  view refs; may be {@code null}
     * @return the dialog (rarely needed; dismissal is automatic on stop)
     */
    public static BottomSheetDialog show(@NonNull Context ctx, @NonNull LifecycleOwner owner,
                                         @NonNull Builder builder, @Nullable Runnable onDismiss) {
        final BottomSheetDialog dlg = new BottomSheetDialog(ctx);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(ContextCompat.getColor(ctx, R.color.k2go_surface));
        content.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 20));

        View handle = new View(ctx);
        handle.setBackgroundColor(ContextCompat.getColor(ctx, R.color.k2go_hairline));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(ctx, 12);
        content.addView(handle, hlp);

        builder.build(dlg, content);

        NestedScrollView scroller = new NestedScrollView(ctx);
        scroller.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        dlg.setContentView(scroller);

        // Modal: open showing the content, not the default collapsed peek.
        dlg.setOnShowListener(d -> {
            View sheet = dlg.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                BottomSheetBehavior<View> b = BottomSheetBehavior.from(sheet);
                b.setSkipCollapsed(true);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        // Lifecycle: dismiss on stop (rotation / background) so the window can never leak. The observer
        // removes itself, and the dismiss hook runs once for either exit (user drag/tap or the stop).
        final Lifecycle lifecycle = owner.getLifecycle();
        final LifecycleEventObserver obs = (src, event) -> {
            if (event == Lifecycle.Event.ON_STOP && dlg.isShowing()) dlg.dismiss();
        };
        lifecycle.addObserver(obs);
        dlg.setOnDismissListener(d -> {
            lifecycle.removeObserver(obs);
            if (onDismiss != null) onDismiss.run();
        });

        dlg.show();
        return dlg;
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
