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
}
