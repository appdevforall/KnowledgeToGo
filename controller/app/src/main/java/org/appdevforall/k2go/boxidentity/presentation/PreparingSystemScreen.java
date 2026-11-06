/*
 * ============================================================================
 * Name        : PreparingSystemScreen.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-549. The "Preparing system" boot screen (view_k2go_preparing.xml).
 * ============================================================================
 */
package org.appdevforall.k2go.boxidentity.presentation;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.util.Motion;

import java.util.Random;

/**
 * Replaces the boot gate while the box makes its per-box values on this start. The host shows it
 * when the box prints its "begin" line and dismisses it when the server is up. Text is composited
 * here, not baked in the Lottie, so one animation serves every locale.
 */
public final class PreparingSystemScreen {

    private static final long WORD_PERIOD_MS = 2500L;
    private static final long WORD_SLIDE_MS = 250L;
    private static final long FADE_OUT_MS = 300L;
    /** The screen never holds longer than this, so a hung box never traps the user. */
    private static final long MAX_HOLD_MS = 180_000L;

    private final View root;
    private final LottieAnimationView anim;
    private final TextView word;
    private final String[] words;
    private final WordCycle cycle;
    private final boolean reduced;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long shownAtMs = -1L;

    private final Runnable holdExpired = this::dismiss;

    private final Runnable nextWord = new Runnable() {
        @Override
        public void run() {
            slideToNextWord();
            handler.postDelayed(this, WORD_PERIOD_MS);
        }
    };

    public PreparingSystemScreen(View root) {
        this.root = root;
        this.anim = root.findViewById(R.id.k2go_preparing_anim);
        this.word = root.findViewById(R.id.k2go_preparing_word);
        this.words = root.getResources().getStringArray(R.array.k2go_preparing_words);
        this.cycle = new WordCycle(words.length, new Random());
        this.reduced = Motion.reduced(root.getContext());
    }

    public boolean isShown() {
        return shownAtMs >= 0L;
    }

    /** Idempotent: a second "begin" in the same boot changes nothing. */
    public void show() {
        if (isShown()) return;
        shownAtMs = SystemClock.elapsedRealtime();
        root.animate().cancel();
        root.setAlpha(1f);
        root.setVisibility(View.VISIBLE);
        anim.setAnimation(R.raw.k2go_preparing);
        anim.setRepeatCount(LottieDrawable.INFINITE);
        word.setText(words[cycle.next()]);
        handler.postDelayed(holdExpired, MAX_HOLD_MS);
        if (reduced) {
            anim.setProgress(0f);   // a still first frame; the text carries the meaning
            return;
        }
        anim.playAnimation();
        handler.postDelayed(nextWord, WORD_PERIOD_MS);
    }

    /**
     * How much longer the gate safety timeout should wait for this screen, or 0 when it should not
     * wait (not shown, or held for {@link #MAX_HOLD_MS} already).
     */
    public long holdRemainingMs() {
        if (!isShown()) return 0L;
        return Math.max(0L, MAX_HOLD_MS - (SystemClock.elapsedRealtime() - shownAtMs));
    }

    /** The boot is done: fade out over whatever the host plays next. */
    public void dismiss() {
        if (!isShown()) return;
        shownAtMs = -1L;
        handler.removeCallbacks(nextWord);
        handler.removeCallbacks(holdExpired);
        if (reduced) {
            hideNow();
            return;
        }
        root.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(this::hideNow).start();
    }

    /** The host is going away: stop the timer so it does not outlive the views. */
    public void release() {
        handler.removeCallbacks(nextWord);
        handler.removeCallbacks(holdExpired);
        root.animate().cancel();
        anim.cancelAnimation();
    }

    private void hideNow() {
        anim.cancelAnimation();
        root.setVisibility(View.GONE);
    }

    /** Game style: the word leaves upward, the next one comes in from below. */
    private void slideToNextWord() {
        final float h = Math.max(word.getHeight(), 1);
        word.animate().cancel();
        word.animate().translationY(-h).alpha(0f).setDuration(WORD_SLIDE_MS).withEndAction(() -> {
            word.setText(words[cycle.next()]);
            word.setTranslationY(h);
            word.animate().translationY(0f).alpha(1f).setDuration(WORD_SLIDE_MS).start();
        }).start();
    }
}
