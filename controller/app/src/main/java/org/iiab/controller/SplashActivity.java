/*
 * File        : SplashActivity.java
 * Description : Branded intro (introduction animation): an animated Aurora background
 *               (light/dark by system), the K2Go logo revealed with a bottom-up
 *               "draw-on" wipe, the "Knowledge To Go" wordmark rising in, and an exit
 *               fade before routing to MainActivity. ADFA-4609.
 * Copyright   : Copyright (c) 2026 AppDevForAll
 */
package org.iiab.controller;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.animation.ValueAnimator;

public class SplashActivity extends AppCompatActivity {

    private static final long EXIT_AT_MS = 3100L;
    private static final long EXIT_FADE_MS = 400L;
    private AuroraView aurora;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int base = dark ? 0xFF0B1020 : 0xFFFFFFFF;
        int textColor = dark ? 0xFFFFFFFF : 0xFF0D0D0D;
        int creditColor = dark ? 0x99FFFFFF : 0x8A0E2A46;

        aurora = findViewById(R.id.aurora);
        aurora.setDark(dark);
        aurora.start();

        TextView word = findViewById(R.id.word);
        TextView credit = findViewById(R.id.credit);
        word.setTextColor(textColor);
        credit.setTextColor(creditColor);

        // Logo "draw-on": reveal bottom -> top via a ClipDrawable wipe, plus a short fade-in.
        ImageView logo = findViewById(R.id.logo);
        Drawable art = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground);
        final ClipDrawable clip = new ClipDrawable(art, Gravity.BOTTOM, ClipDrawable.VERTICAL);
        clip.setLevel(0);
        logo.setImageDrawable(clip);
        logo.setAlpha(0f);
        logo.animate().alpha(1f).setStartDelay(220L).setDuration(520L).start();
        ValueAnimator wipe = ValueAnimator.ofInt(0, 10000);
        wipe.setStartDelay(220L);
        wipe.setDuration(980L);
        wipe.setInterpolator(new DecelerateInterpolator());
        wipe.addUpdateListener(a -> clip.setLevel((int) a.getAnimatedValue()));
        wipe.start();

        // Wordmark rises + fades in after the logo.
        float dy = 16f * getResources().getDisplayMetrics().density;
        word.setAlpha(0f);
        word.setTranslationY(dy);
        word.animate().alpha(1f).translationY(0f).setStartDelay(950L).setDuration(600L).start();

        // Credit fades in last.
        credit.setAlpha(0f);
        credit.animate().alpha(1f).setStartDelay(1450L).setDuration(480L).start();

        // Exit: fade the whole screen to the base color, then route to MainActivity.
        final View fade = findViewById(R.id.fade);
        fade.setBackgroundColor(base);
        fade.setAlpha(0f);
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                fade.animate().alpha(1f).setDuration(EXIT_FADE_MS).withEndAction(() -> {
                    startActivity(new Intent(SplashActivity.this, MainActivity.class));
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    finish();
                }).start(), EXIT_AT_MS);
    }

    @Override
    protected void onDestroy() {
        if (aurora != null) aurora.stop();
        super.onDestroy();
    }
}
