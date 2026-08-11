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
import org.iiab.controller.env.EnvironmentLock;
import org.iiab.controller.install.domain.InterruptedInstallDetector;
import org.iiab.controller.install.presentation.InstallProgressRepository;
import org.iiab.controller.install.presentation.ModuleQueueRepository;
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

    private SystemFactsReader() {
    }

    /**
     * The current facts.
     *
     * <p>Health is {@code InterruptedInstallDetector}'s verdict, asked with <b>all</b>
     * of its preconditions rather than the marker alone. The marker is also held,
     * quite legitimately, by a running install, a running module queue and by a live
     * backup, restore or clone; reading it bare would call a backup in progress a
     * damaged system. That mistake has been made before and cost a false "reinstall"
     * dialog (ADFA-4971), which is why the rule now lives inside the detector with
     * its conditions attached instead of being restated by each caller.
     *
     * <p>The server answer comes from the poll's cache, which is only running while
     * an activity is. Before its first pass the honest answer is "not asked", not
     * "down" — see {@link SystemFacts#isServerStateKnown()}.
     */
    public static SystemFacts read(Context ctx) {
        if (ctx == null) {
            return SystemFacts.none();
        }
        ServerStateRepository server = ServerStateRepository.get();
        boolean serverUp = server.current().alive;
        boolean installed = SystemStateEvaluator.isSystemInstalled(ctx);
        boolean healthy = InterruptedInstallDetector.evaluate(
                InstallGuard.inProgress(ctx),
                InstallProgressRepository.get().isRunning(),
                ModuleQueueRepository.get().isRunning(),
                EnvironmentLock.ownerHeld(ctx),
                serverUp) == InterruptedInstallDetector.Verdict.OK;

        return server.hasObservation()
                ? SystemFacts.of(installed, healthy, serverUp)
                : SystemFacts.serverUnknown(installed, healthy);
    }

    /**
     * ADFA-5061: whether the box is observed to be answering — one fact, no disk.
     *
     * <p>{@link #read} is the way to get the facts, and callers that need several should use
     * it. This exists for the ones that need only this one and would otherwise hand-copy the
     * two lines above — which is how {@code DashboardFragment} came to hold its own version
     * of the installed check, the divergence decision 9 exists to end. Same source, same
     * treatment of "not observed yet": unknown is not up.
     *
     * <p>Cheap enough for a render pass: {@code ServerStateRepository} is the cached
     * observation the server poll already maintains, so this opens no socket and reads no
     * file, which {@link #read} cannot promise.
     */
    public static boolean serverAnswering() {
        ServerStateRepository server = ServerStateRepository.get();
        return server.hasObservation() && server.current().alive;
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
        File dir = SystemStateEvaluator.rootfsDir(ctx);
        return dir.exists() && dir.isDirectory();
    }
}
