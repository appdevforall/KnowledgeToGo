/*
 * ============================================================================
 * Name        : SystemFactsReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Gathers the box's facts in one place, so decisions can be made
 *               from them instead of from nine separate guesses (ADFA-5061).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;

import org.iiab.controller.InstallGuard;
import org.iiab.controller.ServerStateRepository;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.install.domain.InterruptedInstallDetector;
import org.iiab.controller.system.domain.SystemFacts;

import java.io.File;

/**
 * The single reader for "what is true about the box".
 *
 * <p>It <b>delegates</b> rather than reimplements. A survey for ADFA-5061 found nine
 * separate answers to "is a system installed", at three levels of rigour and over
 * two different paths for the same binary, most of them inside legacy god classes.
 * Adding a tenth would have been the joke telling itself, so the canonical one —
 * {@link SystemStateEvaluator#isSystemInstalled} — stays where it is and this class
 * is the one place that asks it.
 *
 * <p><b>Two questions, not one, and that is deliberate.</b> Some of those nine are
 * loose on purpose. {@code InstallService.runPipeline()} asks whether there is a
 * directory to wipe before re-extracting, which is a real question and a different
 * one from whether there is a healthy system; silently upgrading it would change
 * when a rootfs is destroyed, which is the data-loss path ADFA-4758 exists to make
 * safe. So {@link #hasRootfsDirectory} is offered alongside {@link #read}, and each
 * caller migrates to the question it was already asking. Only the indefensible
 * divergences get corrected — a different path to the same binary, a hand-copied
 * evaluator — and those are ADFA-5062.
 *
 * <p><b>Cheap, but not free.</b> Reading the facts touches the filesystem twice and
 * the server-state cache once; it does not open a socket, because the server poll
 * already runs every three seconds and publishes to
 * {@link ServerStateRepository}. Safe on the main thread, but read once per decision
 * rather than once per row.
 */
public final class SystemFactsReader {

    /** Where the rootfs lands. The same path {@code SystemStateEvaluator} uses. */
    private static final String ROOTFS_PATH = "rootfs/installed-rootfs/iiab";

    private SystemFactsReader() {
    }

    /**
     * The current facts.
     *
     * <p>Health is the verdict {@code InterruptedInstallDetector} already gives the
     * boot check, asked the same way: an install marker that is still set while the
     * server is not answering means the install was killed half-way. Reusing it
     * keeps one definition of "damaged" instead of inventing a second.
     */
    public static SystemFacts read(Context ctx) {
        if (ctx == null) {
            return SystemFacts.none();
        }
        boolean serverUp = ServerStateRepository.get().current().alive;
        boolean installed = SystemStateEvaluator.isSystemInstalled(ctx);
        boolean healthy = InterruptedInstallDetector.evaluate(
                InstallGuard.inProgress(ctx), serverUp)
                == InterruptedInstallDetector.Verdict.OK;
        return SystemFacts.of(installed, healthy, serverUp);
    }

    /**
     * Whether anything at all sits at the rootfs path — a directory that exists, no
     * more than that.
     *
     * <p>Not a synonym for "a system is installed": a half-extracted rootfs answers
     * yes here and no to {@link #read}. It exists for the callers whose actual
     * question is "is there something here to wipe or to copy", which is a fair
     * question badly served by the strict check.
     */
    public static boolean hasRootfsDirectory(Context ctx) {
        if (ctx == null) {
            return false;
        }
        File dir = new File(ctx.getFilesDir(), ROOTFS_PATH);
        return dir.exists() && dir.isDirectory();
    }
}
