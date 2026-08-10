/*
 * ============================================================================
 * Name        : ProgressVisual.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Which animation a running operation gets, decided from what the
 *               operation is. Pure JVM, no Android (ADFA-5074).
 * ============================================================================
 */
package org.iiab.controller.system.domain;

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
}
