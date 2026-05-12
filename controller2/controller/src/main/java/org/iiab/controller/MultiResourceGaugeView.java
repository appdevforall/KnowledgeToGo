/*
 * ============================================================================
 * Name        : MultiResourceGaugeView.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Custom view for displaying multiple animated resource gauges
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

import java.util.ArrayList;
import java.util.List;

public class MultiResourceGaugeView extends View {

    private Paint arcPaint, bgArcPaint, percentPaint, valuePaint, titlePaint;
    private RectF rectF;
    private float animatedFactor = 0f;

    private String titleText = "Projected";
    private String centerText = "0%";
    private String bottomText = "-- GB";

    // Structure to hold the pie segments
    public static class Segment {
        public float percentage;
        public int color;

        public Segment(float percentage, int color) {
            this.percentage = percentage;
            this.color = color;
        }
    }

    private List<Segment> segments = new ArrayList<>();

    public MultiResourceGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        rectF = new RectF();

        // 1. Background arc
        bgArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgArcPaint.setStyle(Paint.Style.STROKE);
        bgArcPaint.setColor(androidx.core.content.ContextCompat.getColor(context, R.color.dash_bar_bg));
        bgArcPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(20f);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        // 2. Central Text (0.0G) - Now use the semantic variable
        percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        percentPaint.setTextAlign(Paint.Align.CENTER);
        percentPaint.setColor(androidx.core.content.ContextCompat.getColor(context, R.color.dash_text_inverted));
        percentPaint.setFakeBoldText(true);

        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(androidx.core.content.ContextCompat.getColor(context, R.color.dash_text_secondary));

        // 3. Title ("Storage" / "Projected")
        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setColor(androidx.core.content.ContextCompat.getColor(context, R.color.dash_text_gauge_title));
        titlePaint.setFakeBoldText(true);

        // Font Orbitron
        try {
            Typeface orbitron = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.orbitron);
            percentPaint.setTypeface(orbitron);
            valuePaint.setTypeface(orbitron);
        } catch (Exception e) {}
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int desiredWidth = widthSize;
        int desiredHeight = heightSize;

        if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.UNSPECIFIED) {
            desiredHeight = desiredWidth;
        }
        setMeasuredDimension(desiredWidth, desiredHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h);
        float strokeW = size * 0.07f;
        bgArcPaint.setStrokeWidth(strokeW);
        arcPaint.setStrokeWidth(strokeW);

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = (size / 2f) - (strokeW + 5f);
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius);

        percentPaint.setTextSize(size / 5.0f);
        valuePaint.setTextSize(size / 13f);
        titlePaint.setTextSize(size / 11f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw the entire dark background
        canvas.drawArc(rectF, 135, 270, false, bgArcPaint);

        float startAngle = 135f;

        // Draw each segment sequentially
        for (Segment segment : segments) {
            float targetSweep = 270f * (segment.percentage / 100f);
            float currentSweep = targetSweep * animatedFactor; // Progressive animation

            if (currentSweep > 0) {
                arcPaint.setColor(segment.color);
                arcPaint.setShadowLayer(25f, 0f, 0f, segment.color); // Dynamic glow based on color
                canvas.drawArc(rectF, startAngle, currentSweep, false, arcPaint);
                startAngle += currentSweep; // The next color starts where this one ends
            }
        }

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        canvas.drawText(titleText, centerX, centerY - (percentPaint.getTextSize() * 0.85f), titlePaint);
        canvas.drawText(centerText, centerX, centerY + (percentPaint.getTextSize() * 0.25f), percentPaint);
        canvas.drawText(bottomText, centerX, centerY + (percentPaint.getTextSize() * 0.75f), valuePaint);
    }

    // Main method to inject data
    public void updateData(List<Segment> newSegments, String centerTxt, int centerTxtColor, String bottomTxt, String title) {
        this.titleText = title;
        this.centerText = centerTxt;
        this.bottomText = bottomTxt;
        
        // Apply color and glow to the central text
        this.percentPaint.setColor(centerTxtColor);

        this.segments.clear();
        this.segments.addAll(newSegments);

        // Smooth fill animation
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(900);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            animatedFactor = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }
}
