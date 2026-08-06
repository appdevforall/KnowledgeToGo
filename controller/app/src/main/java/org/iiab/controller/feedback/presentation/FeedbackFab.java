package org.iiab.controller.feedback.presentation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

/**
 * Draggable feedback FAB with position persistence, recycled from Code On the Go's
 * FeedbackButtonManager. Distinguishes a tap (fires {@code onTap}) from a drag (moves
 * the button and stores its position as normalized ratios). ADFA-4538.
 */
public final class FeedbackFab {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY_X = "fab_x_ratio";
    private static final String KEY_Y = "fab_y_ratio";

    private FeedbackFab() {
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void attach(FloatingActionButton fab, Runnable onTap) {
        if (fab == null) {
            return;
        }
        final Context ctx = fab.getContext();
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        fab.post(() -> applySaved(fab));
        fab.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> applySaved(fab));

        fab.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, dX, dY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dX = v.getX() - e.getRawX();
                        dY = v.getY() - e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) {
                            dragging = true;
                        }
                        if (dragging) {
                            ViewGroup parent = (ViewGroup) v.getParent();
                            if (parent == null) {
                                return true;
                            }
                            float nx = clamp(e.getRawX() + dX, 0, parent.getWidth() - v.getWidth());
                            float ny = clamp(e.getRawY() + dY, 0, parent.getHeight() - v.getHeight());
                            v.setX(nx);
                            v.setY(ny);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            savePosition(fab);
                        } else {
                            v.performClick();
                            if (onTap != null) {
                                onTap.run();
                            }
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) {
            return lo;
        }
        return Math.max(lo, Math.min(v, hi));
    }

    private static void applySaved(FloatingActionButton fab) {
        ViewGroup parent = (ViewGroup) fab.getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }
        SharedPreferences p = fab.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float xr = p.getFloat(KEY_X, -1f);
        float yr = p.getFloat(KEY_Y, -1f);
        if (xr < 0 || yr < 0) {
            return;
        }
        float availX = parent.getWidth() - fab.getWidth();
        float availY = parent.getHeight() - fab.getHeight();
        fab.setX(clamp(xr * availX, 0, availX));
        fab.setY(clamp(yr * availY, 0, availY));
    }

    private static void savePosition(FloatingActionButton fab) {
        ViewGroup parent = (ViewGroup) fab.getParent();
        if (parent == null) {
            return;
        }
        float availX = parent.getWidth() - fab.getWidth();
        float availY = parent.getHeight() - fab.getHeight();
        if (availX <= 0 || availY <= 0) {
            return;
        }
        fab.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_X, clamp(fab.getX() / availX, 0, 1))
                .putFloat(KEY_Y, clamp(fab.getY() / availY, 0, 1))
                .apply();
    }

    /** ADFA-4932: add the draggable feedback FAB to any activity's content root (no per-layout
     *  edit) and wire tap -> screenshot -> email. Reusable across the redesign screens. Idempotent. */
    public static void installOn(android.app.Activity activity, String screenTag) {
        installOn(activity, screenTag, 16);   // default: flush to the bottom (no bottom nav)
    }

    /** ADFA-4932: {@code bottomMarginDp} lets screens with a bottom nav (LibraryActivity) lift the
     *  FAB clear of it; nav-less screens pass the default 16dp. */
    public static void installOn(android.app.Activity activity, String screenTag, int bottomMarginDp) {
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null || root.findViewById(org.iiab.controller.R.id.fab_feedback) != null) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        FloatingActionButton fab = new FloatingActionButton(activity);
        fab.setId(org.iiab.controller.R.id.fab_feedback);
        fab.setImageResource(org.iiab.controller.R.drawable.ic_feedback_24);
        fab.setContentDescription(activity.getString(org.iiab.controller.R.string.feedback_send));
        fab.setUseCompatPadding(true);
        // ADFA-4947: the default FAB background resolved to the same surface as k2go_card_bg
        // (k2go_surface) in both themes, so this floating, draggable button blended into whatever card
        // it hovered over. Tint it with the teal accent + on-teal icon (both flip day/night) so it
        // stays clearly distinct from the cards in either theme (the FAB's default elevation already
        // gives the floating shadow).
        fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(activity, org.iiab.controller.R.color.k2go_teal)));
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(activity, org.iiab.controller.R.color.k2go_on_teal)));
        int m = Math.round(16 * d);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.END);
        lp.setMargins(m, m, m, Math.round(bottomMarginDp * d));
        root.addView(fab, lp);
        attach(fab, () -> sendFeedback(activity, screenTag));
    }

    /** ADFA-4538/4932: capture a screenshot, build the diagnostics payload, and hand it to the
     *  configured transport (mailto -> mail chooser). Shared by MainActivity and the redesign. */
    public static void sendFeedback(android.app.Activity activity, String screen) {
        org.iiab.controller.feedback.data.FeedbackScreenshot.capture(activity, path -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;   // ADFA-4932: activity gone between tap and the async capture callback
            }
            org.iiab.controller.feedback.domain.FeedbackPayload payload =
                    org.iiab.controller.feedback.domain.FeedbackPayload
                            .builder(org.iiab.controller.feedback.domain.FeedbackType.GENERAL)
                            .appVersion(org.iiab.controller.feedback.data.FeedbackDiagnostics.appVersionName(activity))
                            .appBuild(org.iiab.controller.feedback.data.FeedbackDiagnostics.appVersionCode(activity))
                            .androidRelease(android.os.Build.VERSION.RELEASE)
                            .device(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                            .abi(new org.iiab.controller.deviceinfo.domain.GetDeviceArchUseCase(
                                    new org.iiab.controller.deviceinfo.data.BuildDeviceAbiProvider()).execute())
                            .binariesTag(org.iiab.controller.feedback.data.FeedbackDiagnostics.binariesTag(activity))
                            .screen(screen)
                            .screenshot(path)
                            .build();
            boolean ok = org.iiab.controller.feedback.data.FeedbackConfig.create(activity)
                    .send(activity, payload);
            if (ok) {
                org.iiab.controller.analytics.AnalyticsClient.with(activity).logFeedbackSent();
            } else {
                android.widget.Toast.makeText(activity, org.iiab.controller.R.string.feedback_no_email_app,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }
}
