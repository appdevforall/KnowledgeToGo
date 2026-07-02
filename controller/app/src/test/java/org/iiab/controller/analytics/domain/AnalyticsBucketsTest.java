package org.iiab.controller.analytics.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AnalyticsBucketsTest {
    @Test public void buckets() {
        assertEquals("unknown", AnalyticsBuckets.uptimeBucket(-1));
        assertEquals("lt_1m", AnalyticsBuckets.uptimeBucket(0));
        assertEquals("lt_1m", AnalyticsBuckets.uptimeBucket(59_000));
        assertEquals("1_5m", AnalyticsBuckets.uptimeBucket(60_000));
        assertEquals("5_15m", AnalyticsBuckets.uptimeBucket(5 * 60_000));
        assertEquals("15_60m", AnalyticsBuckets.uptimeBucket(15 * 60_000));
        assertEquals("1_4h", AnalyticsBuckets.uptimeBucket(60 * 60_000));
        assertEquals("gt_4h", AnalyticsBuckets.uptimeBucket(4 * 60 * 60_000));
    }
}
