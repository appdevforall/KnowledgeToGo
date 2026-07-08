/*
 * ============================================================================
 * Name        : TooltipTag.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Stable tooltip tags (anchor-point keys) for the K2Go
 *               three-tier help system. One constant per native control.
 * ============================================================================
 */
package org.iiab.controller.help;

/**
 * Tags are stable dotted keys "<screen>.<control>" used to look up a tooltip
 * in the help database. Keep them in sync with the anchor-point inventory in
 * ADFA-4536-three-tier-help-analysis.md.
 *
 * This scaffold defines the wired subset plus a few examples; the remaining
 * anchor points are added as their screens are wired (see ADFA-4593).
 */
public final class TooltipTag {

    private TooltipTag() {}

    // --- Main screen (wired in this scaffold) ---
    public static final String MAIN_SETTINGS      = "main.settings";
    public static final String MAIN_SHARE_QR      = "main.share_qr";
    public static final String MAIN_THEME_TOGGLE  = "main.theme_toggle";
    public static final String MAIN_VERSION       = "main.version";

    // --- Usage screen (examples; wiring deferred to avoid conflicts with the
    //     dead-code cleanup that also edits fragment_usage) ---
    public static final String USAGE_WIFI            = "usage.wifi";
    public static final String USAGE_HOTSPOT         = "usage.hotspot";
    public static final String USAGE_BROWSE_CONTENT  = "usage.browse_content";
    public static final String USAGE_SERVER_CONTROL  = "usage.server_control";
}
