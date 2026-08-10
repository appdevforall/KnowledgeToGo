/*
 * ============================================================================
 * Name        : ProgressVisuals.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Turns a resolved ProgressVisual into the asset that plays it.
 *               The one place a file name appears (ADFA-5074).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import androidx.annotation.RawRes;

import org.iiab.controller.R;
import org.iiab.controller.system.domain.ContentType;
import org.iiab.controller.system.domain.Operation;
import org.iiab.controller.system.domain.ProgressVisual;

/**
 * The Android half of the progress animation: which raw resource plays which intent, and
 * how a progress row's key becomes an operation to ask about.
 *
 * <p>Kept apart from {@link ProgressVisual} so the rule — live work downloads, stopped work
 * builds — stays unit-testable without a device, and so a file name appears exactly once in
 * the app.
 */
public final class ProgressVisuals {

    private ProgressVisuals() {
    }

    /** Prefix the install index uses for a proot module row. */
    private static final String MODULE_PREFIX = "mod:";

    /**
     * The asset for an intent.
     *
     * <p>{@code BUILD} deliberately plays the download art for now: Maps wants something of
     * its own and does not have it yet, and a placeholder beats a second copy of the same
     * file. Giving it one is this line, and no layout changes.
     */
    @RawRes
    public static int rawResFor(ProgressVisual visual) {
        switch (visual) {
            case BUILD:
            case DOWNLOAD:
            default:
                return R.raw.k2go_working_loop;
        }
    }

    /**
     * The intent for one of the index's row keys.
     *
     * <p>The keys are a presentation vocabulary — {@code "zim"}, {@code "books"},
     * {@code "kolibri"}, {@code "maps"}, and {@code "mod:<name>"} for a proot module — so
     * turning one into an {@link Operation} belongs here rather than in the domain. Anything
     * unrecognised is treated as content, which is the case three of the four types are in.
     */
    public static ProgressVisual visualForKey(String key) {
        if (key == null) {
            return ProgressVisual.DOWNLOAD;
        }
        if (key.startsWith(MODULE_PREFIX)) {
            return ProgressVisual.forOperation(
                    Operation.appInstall(key.substring(MODULE_PREFIX.length())));
        }
        ContentType type = ContentType.byKey(key);
        return type == null ? ProgressVisual.DOWNLOAD : ProgressVisual.forContent(type);
    }

    /** The asset for one of the index's row keys, in one call. */
    @RawRes
    public static int rawResForKey(String key) {
        return rawResFor(visualForKey(key));
    }

    /**
     * Loads the right animation into a card that included
     * {@code view_k2go_working_anim}.
     *
     * <p>One line per card, and the only line any card needs: the block itself declares no
     * asset, so nothing plays until this runs. That is deliberate — a layout that could show
     * an animation on its own is a layout that can disagree with the rule.
     *
     * @param root the inflated card
     * @param type what the card is adding; null falls back to the download art
     */
    public static void apply(android.view.View root, ContentType type) {
        if (root == null) {
            return;
        }
        com.airbnb.lottie.LottieAnimationView anim = root.findViewById(R.id.k2go_working_anim);
        if (anim == null) {
            return;   // a card that chose not to include the block
        }
        anim.setAnimation(rawResFor(ProgressVisual.forContent(type)));
        anim.playAnimation();
    }

    /** The same, for a proot module install rather than a content type. */
    public static void applyForModule(android.view.View root) {
        if (root == null) {
            return;
        }
        com.airbnb.lottie.LottieAnimationView anim = root.findViewById(R.id.k2go_working_anim);
        if (anim == null) {
            return;
        }
        anim.setAnimation(rawResFor(ProgressVisual.forOperation(Operation.appInstall(""))));
        anim.playAnimation();
    }
}
