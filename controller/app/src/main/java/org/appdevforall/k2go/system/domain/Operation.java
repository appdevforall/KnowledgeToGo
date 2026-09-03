/*
 * ============================================================================
 * Name        : Operation.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : What a user-facing operation IS — declared once, never re-derived.
 *               Pure JVM, no Android (ADFA-5061).
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * A user-facing operation, described by what it is rather than by how some screen
 * happens to reach it.
 *
 * <p>Two axes, and keeping them apart is the whole point (ADR-5061). The UI has been
 * conflating them: one "Books" card meaning both *install Calibre-Web* and *add
 * books*, one "Maps" name covering both a `runrole` install and an FQR region
 * download, one Rebuild button forking on an invisible version number.
 *
 * <ul>
 *   <li>{@link Kind} — <em>what</em> it acts on: the system itself, a platform's
 *       app, or content inside a platform.</li>
 *   <li>{@link ExecutionClass} — <em>how</em> it runs: with the box up, over REST,
 *       or with the box stopped, under proot.</li>
 * </ul>
 *
 * <p>The class is declared here and never inferred from an endpoint string, a key
 * prefix, an on-disk version or a field on an activity. Where it genuinely depends
 * on state — the dashboard rebuild is live on dash-node ≥ 1.2.0 and stopped below —
 * the caller resolves the class first and then builds the operation with it, so the
 * dependence is visible instead of buried in a fallback.
 *
 * <p>Immutable.
 */
public final class Operation {

    /** What the operation acts on. */
    public enum Kind {
        /** The rootfs itself: install, reinstall, reset, restore. */
        SYSTEM,
        /** A platform's app — Kolibri, Calibre-Web, the Kiwix reader, the Maps module. */
        APP_INSTALL,
        /** Content inside a platform: channels, ZIMs, books, map regions. */
        CONTENT
    }

    /** How the operation runs. */
    public enum ExecutionClass {
        /**
         * The box stays up; the device POSTs and polls the in-server REST core. The
         * user can keep using the system and its existing content meanwhile.
         */
        LIVE,
        /**
         * The box goes down: {@code pdsm stop}, then Ansible in a transient proot.
         * Only one may run at a time, and it cannot be entered once started, so the
         * user has to be gated to the progress screen rather than left to wander.
         */
        STOPPED
    }

    private final String platform;
    private final Kind kind;
    private final ExecutionClass executionClass;

    private Operation(String platform, Kind kind, ExecutionClass executionClass) {
        this.platform = platform;
        this.kind = kind;
        this.executionClass = executionClass;
    }

    /**
     * @param platform the content platform this belongs to — {@code kolibri},
     *                 {@code books}, {@code maps}, {@code kiwix}, {@code dashboard}.
     *                 Empty for an operation on the system itself.
     */
    public static Operation of(String platform, Kind kind, ExecutionClass executionClass) {
        if (kind == null || executionClass == null) {
            throw new IllegalArgumentException("an operation must declare its kind and class");
        }
        return new Operation(platform == null ? "" : platform.trim(), kind, executionClass);
    }

    /** Installing or replacing the rootfs. Always stopped: it is the box. */
    public static Operation system() {
        return new Operation("", Kind.SYSTEM, ExecutionClass.STOPPED);
    }

    /** Installing a platform's app with {@code runrole}. Always stopped. */
    public static Operation appInstall(String platform) {
        return new Operation(platform == null ? "" : platform.trim(),
                Kind.APP_INSTALL, ExecutionClass.STOPPED);
    }

    /** Adding or removing content over REST. Always live — see {@link OperationDispatcher}
     *  for what happens when it is asked for before there is a box to be live against. */
    public static Operation content(String platform) {
        return new Operation(platform == null ? "" : platform.trim(),
                Kind.CONTENT, ExecutionClass.LIVE);
    }

    /** The platform this belongs to, or empty for a system operation. */
    public String platform() {
        return platform;
    }

    public Kind kind() {
        return kind;
    }

    public ExecutionClass executionClass() {
        return executionClass;
    }

    public boolean isLive() {
        return executionClass == ExecutionClass.LIVE;
    }

    @Override
    public String toString() {
        return "Operation{" + (platform.isEmpty() ? "system" : platform)
                + ", " + kind + ", " + executionClass + "}";
    }
}
