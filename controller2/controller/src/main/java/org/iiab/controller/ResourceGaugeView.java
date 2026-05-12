/*
 * ============================================================================
 * Name        : ResourceGaugeView.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Custom view for displaying animated resource gauges
 * ============================================================================
 */
package org.iiab.controller;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class ResourceGaugeView extends View {

    private Paint arcPaint, bgArcPaint, percentPaint, valuePaint, titlePaint;
    private RectF rectF;
    private float currentProgress = 0f;
    private float animatedProgress = 0f;

    private String titleText = "Resource";
    private String centerText = "0%";
    private String bottomText = "-- / --";

    private int currentColor = Color.parseColor("#4CAF50");

    public ResourceGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        rectF = new RectF();

        bgArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgArcPaint.setStyle(Paint.Style.STROKE);
        bgArcPaint.setColor(Color.parseColor("#333333"));
        bgArcPaint.setStrokeCap(Paint.Cap.ROUND);
        bgArcPaint.setPathEffect(null);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(20f);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        // Light up first glow
        arcPaint.setShadowLayer(25f, 0f, 0f, currentColor);

        percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        percentPaint.setTextAlign(Paint.Align.CENTER);
        percentPaint.setColor(androidx.core.content.ContextCompat.getColor(context, R.color.dash_text_inverted));
        percentPaint.setFakeBoldText(true);

        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(Color.parseColor("#AAAAAA"));

        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setColor(Color.parseColor("#CCCCCC"));
        titlePaint.setFakeBoldText(true);

        // LOAD AND APPLY ORBITRON
        try {
            Typeface orbitron = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.orbitron);
            percentPaint.setTypeface(orbitron);
            valuePaint.setTypeface(orbitron);
        } catch (Exception e) {
            // Silent fallback in case Android cannot find the font
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int desiredWidth = widthSize;
        int desiredHeight = heightSize;

        // PORTRAIT ANIMATION
        if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.UNSPECIFIED) {
            desiredHeight = desiredWidth;
        }

        setMeasuredDimension(desiredWidth, desiredHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // LANDSCAPE ANIMATION (Perfectly Centered)
        float size = Math.min(w, h);

        float strokeW = size * 0.07f;
        bgArcPaint.setStrokeWidth(strokeW);
        arcPaint.setStrokeWidth(strokeW);

        float cx = w / 2f;
        float cy = h / 2f;

        // Calculate the radius of the circle
        float radius = (size / 2f) - (strokeW + 5f);

        // Anchor the drawing rectangle exactly in the center of the view
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // Dynamic scale of fonts
        percentPaint.setTextSize(size / 5.0f);
        valuePaint.setTextSize(size / 15f);
        titlePaint.setTextSize(size / 11f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawArc(rectF, 135, 270, false, bgArcPaint);

        arcPaint.setColor(currentColor);
        float sweepAngle = 270 * (animatedProgress / 100f);
        canvas.drawArc(rectF, 135, sweepAngle, false, arcPaint);

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        // VERTICAL COMPACTION OF TEXTS
        canvas.drawText(titleText, centerX, centerY - (percentPaint.getTextSize() * 0.85f), titlePaint);
        canvas.drawText(centerText, centerX, centerY + (percentPaint.getTextSize() * 0.25f), percentPaint);
        canvas.drawText(bottomText, centerX, centerY + (percentPaint.getTextSize() * 0.75f), valuePaint);
    }

    // Force update animation on touch
    public void triggerAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, currentProgress);
        animator.setDuration(1000);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            animatedProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    // Main method for updating the animated bar with explicit color definition
    public void updateData(float progress, String values, String title, int customColor) {
        this.titleText = title;
        this.currentColor = customColor;
        this.bottomText = values;
        this.centerText = (int) progress + "%";

        arcPaint.setShadowLayer(25f, 0f, 0f, currentColor);

        // Avoid unnecessary animations if the value is the same
        if (this.currentProgress == progress) {
            invalidate();
            return;
        }

        // Smooth fill animation
        ValueAnimator animator = ValueAnimator.ofFloat(this.currentProgress, progress);
        animator.setDuration(800);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            animatedProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();

        this.currentProgress = progress;
    }
}