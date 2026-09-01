/*
 * ============================================================================
 * Name        : ProgressVisual.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Which animation a running operation gets, decided from what the
 *               operation is. Pure JVM, no Android (ADFA-5074).
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * What the progress screen should be showing while an operation runs.
 *
 * <p><b>Why this is resolved rather than placed.</b> The working animation lived in one
 * fragment's layout — ZIM's — so Courses had none and Maps had none. Copying it into each
 * layout would have recreated the drift this exists to remove: hand-kept copies that agree
 * until someone edits one. Putting a single animation in the shared host instead would have
 * traded that duplication for a lie: the art is a cloud sending data to the device, which is
 * literal for ZIM, Books and Courses and false for Maps, an Ansible runrole building tiles
 * with the server stopped.
 *
 * <p>So it is shared along the axis that already exists. The
 * {@link Operation.ExecutionClass} decides: live work is a download, stopped work is a
 * build. One rule, no per-type layout owning an animation.
 *
 * <p><b>Where a per-module exception goes.</b> Inside {@link #forOperation}, as a case on
 * the platform name <em>before</em> the class fallback. Nothing needs one today; the point
 * of resolving rather than placing is that when something does, it is a line here and no
 * layout is touched.
 *
 * <p>This enum names the <em>intent</em>, not a file. Which asset each intent maps to is a
 * presentation concern, so this stays testable on a plain JVM.
 */
public enum ProgressVisual {

    /** Something is coming down from the network. The server does the transfer. */
    DOWNLOAD,

    /**
     * Something is being built on the device, with the box stopped — a runrole. Deliberately
     * mapped to the same asset as {@link #DOWNLOAD} for now: Maps wants art of its own and
     * does not have it yet, and a placeholder beats a second copy. Changing that is one line
     * in the presentation mapping.
     */
    BUILD;

    /**
     * @param op what is running; null answers {@link #DOWNLOAD}, the common case, rather
     *           than throwing at a screen that only wants something to draw
     */
    public static ProgressVisual forOperation(Operation op) {
        if (op == null) {
            return DOWNLOAD;
        }
        // A per-platform exception would go here, on op.platform(), before the fallback.
        return op.isLive() ? DOWNLOAD : BUILD;
    }

    /** The visual for adding content of this type. */
    public static ProgressVisual forContent(ContentType type) {
        return type == null ? DOWNLOAD : forOperation(type.operation());
    }

    /** The visual for installing a platform's app — a runrole, so always a build. */
    public static ProgressVisual forModuleInstall(String moduleKey) {
        return forOperation(Operation.appInstall(moduleKey == null ? "" : moduleKey));
    }

    /**
     * The visual for one of the progress index's row keys.
     *
     * <p>The keys are {@code "zim"}, {@code "books"}, {@code "kolibri"}, {@code "maps"} and
     * {@code "mod:<name>"} for a proot module. {@link ContentType} already owns the first
     * four, so the prefix is parsed here rather than in presentation — and the parsing is
     * the part that breaks quietly, so it is the part with a test.
     *
     * <p>An unrecognised key is treated as content, which is the case three of the four
     * types are in.
     */
    public static ProgressVisual forKey(String key) {
        if (key == null) {
            return DOWNLOAD;
        }
        if (key.startsWith(MODULE_PREFIX)) {
            return forModuleInstall(key.substring(MODULE_PREFIX.length()));
        }
        return forContent(ContentType.byKey(key));
    }

    /** Prefix the progress index uses for a proot module row. */
    public static final String MODULE_PREFIX = "mod:";

    // TODO(ADFA-5074): this model is deliberately incomplete. The app has four working
    // animations — k2go_working_loop, k2go_backup_loop, k2go_restore_loop — and this enum
    // names two. BackupJobFragment (ADFA-4961) already resolves its own from the mode, by
    // the same principle: the layout declares no asset and the code decides from what the
    // operation is. It was written first and is not folded in here because backup and
    // restore are system operations rather than content, which is a wider change. Whoever
    // extends this should extend that precedent rather than build a third mechanism.
}
