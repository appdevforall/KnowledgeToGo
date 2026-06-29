/*
 * ============================================================================
 * Name        : ShareConfig.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure value object for the Share feature's transport configuration
 *               — rsync port, APK-server port, auth user, and rsync module name.
 *               Replaces the hardcoded constants scattered across SyncFragment /
 *               RsyncManager (tech-debt S7) with a single configurable source.
 *               No android.*; clonable by an external host (Share-export, S14
 *               step 2). An external host (e.g. Code on the Go) picks its own
 *               module tag / ports via a different ShareConfig.
 * ============================================================================
 */
package org.iiab.controller.sync.domain;

public final class ShareConfig {

    /** TCP port the rsync daemon listens on / the client connects to. */
    public final int rsyncPort;
    /** TCP port the plain-HTTP APK server listens on. */
    public final int apkPort;
    /** rsync auth user (also the rsync:// URL userinfo). */
    public final String user;
    /** rsync module name (the {@code [name]} section + URL path segment). */
    public final String moduleName;

    public ShareConfig(int rsyncPort, int apkPort, String user, String moduleName) {
        this.rsyncPort = rsyncPort;
        this.apkPort = apkPort;
        this.user = user;
        this.moduleName = moduleName;
    }

    /** The IIAB defaults previously hardcoded (rsync 8730, APK 8080, iiab_peer, iiab_sync). */
    public static ShareConfig defaults() {
        return new ShareConfig(8730, 8080, "iiab_peer", "iiab_sync");
    }
}
