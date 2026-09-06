/*
 * ============================================================================
 * Name        : OpReturnNavigator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-382. ONE source for "return to a running operation's own screen."
 *
 *               Every deep-op foreground notification (install / backup / restore / dashboard
 *               rebuild / clone) and every content-download stream (kiwix / books / kolibri) must,
 *               when tapped, land on that op's own progress screen -- not on Home/Settings. That
 *               "op -> screen" mapping used to be spelled out separately in each service's
 *               contentIntent AND in LibraryActivity's relaunch re-route, so the two could drift
 *               (they did: several services opened a bare LibraryActivity that fell back to the last
 *               tab). This class is the single owner of the mapping.
 *
 *               Lifecycle / state: this class is STATELESS. It holds no "what is running" flag of its
 *               own; {@link #forActiveOp} reads the existing run-state repositories (the module queue
 *               and the deep-op repository), which stay the single source of truth for what is live.
 *
 *               Flags: the per-op builders return the target + extras only (the "where"). Notification
 *               callers wrap them with {@link #notify} (which adds the from-a-service task flags, so a
 *               tap brings an existing screen forward instead of stacking a duplicate). Activity
 *               callers start the bare intent in their own task and add flags as their context needs.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.deepop.DeepOpProgressRepository;
import org.appdevforall.k2go.deepop.DeepOpState;
import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;

public final class OpReturnNavigator {
    private OpReturnNavigator() {}

    /** Content downloads (kiwix / books / kolibri): the shared provisioning progress screen. */
    public static Intent contentDownload(Context ctx) {
        return new Intent(ctx, SetupProgressActivity.class);
    }

    /** Backup / restore live op: the backup/restore job screen; BackupJobFragment re-binds to the
     *  running op from DeepOpProgressRepository (ADFA-4957). */
    public static Intent backupRestore(Context ctx, EnvironmentLock.Owner owner) {
        String mode = owner == EnvironmentLock.Owner.RESTORE
                ? BackupJobFragment.MODE_RESTORE : BackupJobFragment.MODE_BACKUP;
        return new Intent(ctx, SetupLibraryActivity.class)
                .putExtra(SetupLibraryActivity.EXTRA_BACKUP_RESTORE, true)
                .putExtra(SetupLibraryActivity.EXTRA_BR_JOB_MODE, mode);
    }

    /** Dashboard rebuild: the module-management dashboard card with its in-progress indicator. */
    public static Intent dashboardRebuild(Context ctx) {
        return new Intent(ctx, SetupLibraryActivity.class)
                .putExtra(SetupLibraryActivity.EXTRA_MODULE_MGMT, true)
                .putExtra(SetupLibraryActivity.EXTRA_DASHBOARD_DETAIL, true);
    }

    /** Rootfs / module install: a bare LibraryActivity. It detects a live rootfs install itself
     *  (InstallProgressRepository.isRunning -> the boot-gate progress) and, when a proot module queue
     *  is live instead, its relaunch re-route ({@link #forActiveOp}) forwards to the install index.
     *  Do NOT set EXTRA_INSTALLING here: it forces LibraryActivity.installing=true, which suppresses
     *  that module re-route and would strand a module install's notification on the gate. */
    public static Intent install(Context ctx) {
        return new Intent(ctx, LibraryActivity.class);
    }

    /** Clone / share: the Connect/clone tab. */
    public static Intent cloneShare(Context ctx) {
        return new Intent(ctx, LibraryActivity.class)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_clone);
    }

    /**
     * The live op's screen, chosen from the run-state repositories -- the single place that decides
     * "which op is running." Returns {@code null} when nothing tracked here is live (the caller keeps
     * its normal destination). Mirrors the order LibraryActivity used: a proot module queue first,
     * then a deep-env op (they are mutually exclusive by ContentAdmission, but the order is kept).
     */
    public static Intent forActiveOp(Context ctx) {
        if (ModuleQueueRepository.get().isRunning()) {
            return contentDownload(ctx);   // the module install index lives on the progress screen
        }
        if (DeepOpProgressRepository.get().isRunning()) {
            DeepOpState dop = DeepOpProgressRepository.get().current();
            if (dop != null) return backupRestore(ctx, dop.owner);
        }
        return null;
    }

    /**
     * Wrap a per-op intent as a notification contentIntent. Adds the from-a-service task flags
     * (NEW_TASK is required when a Service launches an Activity; SINGLE_TOP|CLEAR_TOP bring an
     * existing op screen forward instead of stacking a duplicate) -- one policy for every deep-op
     * notification, so none can drift (this also supplies the NEW_TASK that clone was missing).
     */
    public static PendingIntent notify(Context ctx, Intent target) {
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 0, target,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
