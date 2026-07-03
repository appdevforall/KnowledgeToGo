/*
 * ============================================================================
 * Name        : AnalyticsBuckets.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure bucketing for analytics numeric params (ADFA-4466 Phase 1).
 *               We report coarse buckets, never exact values, so timing/uptime
 *               cannot be used to fingerprint a device. No Android deps -> JVM
 *               unit-testable.
 * ============================================================================
 */
package org.iiab.controller.analytics.domain;

public final class AnalyticsBuckets {

    private AnalyticsBuckets() {
    }

    /** Coarse server-uptime bucket from a duration in milliseconds. */
    public static String uptimeBucket(long ms) {
        if (ms < 0) return "unknown";
        long min = ms / 60000L;
        if (min < 1) return "lt_1m";
        if (min < 5) return "1_5m";
        if (min < 15) return "5_15m";
        if (min < 60) return "15_60m";
        if (min < 240) return "1_4h";
        return "gt_4h";
    }
}
