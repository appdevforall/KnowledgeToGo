/*
 * ============================================================================
 * Name        : TooltipCategory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Tooltip category constants for the K2Go three-tier help system.
 * ============================================================================
 */
package org.iiab.controller.help;

/**
 * Categories partition the tooltip namespace (mirrors Code On the Go's model,
 * where categories are ide/java/kotlin/xml). K2Go starts with a single native
 * category; more can be added later without schema changes.
 */
public final class TooltipCategory {

    private TooltipCategory() {}

    /** Native K2Go UI (tiers 1 & 2 live in the APK). */
    public static final String K2GO = "k2go";
}
