/*
 * ============================================================================
 * Name        : BoxEndpoints.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Single source of the on-device box base URL (tech-debt D3, ADFA-4713).
 * ============================================================================
 */
package org.iiab.controller.config;

/**
 * One place for the local box's HTTP base (nginx on :8085). Each feature appends its own
 * path (e.g. BASE + "/home", BASE + "/pdfjs/..."). Pure constant, no android.*.
 * Sync/rsync ports live in {@code sync.domain.ShareConfig} (a separate concern).
 */
public final class BoxEndpoints {

    private BoxEndpoints() {}

    /** Scheme + host + port of the box's nginx services. No trailing slash. */
    public static final String BASE = "http://localhost:8085";
}
