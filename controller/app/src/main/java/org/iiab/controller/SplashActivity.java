/*
 * File        : SplashActivity.java
 * Description : Branded intro: the K2Go logo and "Knowledge To Go" wordmark fading in on a
 *               plain background (light/dark by system), then an exit fade to MainActivity.
 * Copyright   : Copyright (c) 2026 AppDevForAll
 */
package org.iiab.controller;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;


public class SplashActivity extends AppCompatActivity {

    private static final long EXIT_AT_MS = 3100L;
    private static final long EXIT_FADE_MS = 400L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int base = dark ? 0xFF0B1020 : 0xFFFFFFFF;
        int textColor = dark ? 0xFFFFFFFF : 0xFF0D0D0D;
        int creditColor = dark ? 0x99FFFFFF : 0x8A0E2A46;

        findViewById(R.id.splash_root).setBackgroundColor(base);

        TextView word = findViewById(R.id.word);
        TextView credit = findViewById(R.id.credit);
        word.setTextColor(textColor);
        credit.setTextColor(creditColor);

        // Logo reveal: simple fade-in.
        ImageView logo = findViewById(R.id.logo);
        logo.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground));
        logo.setAlpha(0f);
        logo.animate().alpha(1f).setStartDelay(220L).setDuration(700L).start();

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
        super.onDestroy();
    }
}
