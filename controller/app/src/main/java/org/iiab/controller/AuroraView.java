/*
 * File        : AuroraView.java
 * Description : Animated "Aurora" background for the intro screen — soft drifting
 *               radial-gradient blobs (brand green/blue + accents) over a base color
 *               that follows the system light/dark mode. No blur needed (radial
 *               gradients are inherently soft), so it runs on API 24+. ADFA-4609.
 * Copyright   : Copyright (c) 2026 AppDevForAll
 */
package org.iiab.controller;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class AuroraView extends View {

    private static final class Blob {
        final int color;
        final float baseX, baseY, r, driftX, driftY, phase;
        Blob(int color, float baseX, float baseY, float r, float driftX, float driftY, float phase) {
            this.color = color; this.baseX = baseX; this.baseY = baseY;
            this.r = r; this.driftX = driftX; this.driftY = driftY; this.phase = phase;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int baseColor = 0xFFFFFFFF;
    private Blob[] blobs;
    private ValueAnimator anim;
    private float t = 0f;

    public AuroraView(Context c) { super(c); }
    public AuroraView(Context c, AttributeSet a) { super(c, a); }
    public AuroraView(Context c, AttributeSet a, int s) { super(c, a, s); }

    /** Configure palette for light or dark mode. */
    public void setDark(boolean dark) {
        baseColor = dark ? 0xFF0B1020 : 0xFFFFFFFF;
        int a = dark ? 0xB0 : 0x8C;
        int green = (a << 24) | 0x17A05A;
        int blue  = (a << 24) | 0x1E77E6;
        int mint  = (a << 24) | 0x8FDCB0;
        int sky   = (a << 24) | 0xBFE0FF;
        blobs = new Blob[]{
            new Blob(green, 0.16f, 0.14f, 0.60f,  0.06f, -0.05f, 0.00f),
            new Blob(blue,  0.86f, 0.88f, 0.55f, -0.05f,  0.05f, 0.33f),
            new Blob(mint,  0.34f, 0.52f, 0.42f,  0.05f,  0.04f, 0.60f),
            new Blob(sky,   0.82f, 0.20f, 0.42f, -0.04f,  0.05f, 0.15f)
        };
        invalidate();
    }

    public void start() {
        if (anim != null) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(9000L);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    public void stop() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(baseColor);
        if (blobs == null) return;
        int w = getWidth(), h = getHeight();
        float maxWH = Math.max(w, h);
        for (Blob b : blobs) {
            double ph = (t + b.phase) * 2.0 * Math.PI;
            float cx = (b.baseX + b.driftX * (float) Math.sin(ph)) * w;
            float cy = (b.baseY + b.driftY * (float) Math.cos(ph)) * h;
            float rad = b.r * maxWH;
            paint.setShader(new RadialGradient(cx, cy, rad,
                    new int[]{ b.color, b.color & 0x00FFFFFF },
                    new float[]{ 0f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, rad, paint);
        }
        paint.setShader(null);
    }
}
