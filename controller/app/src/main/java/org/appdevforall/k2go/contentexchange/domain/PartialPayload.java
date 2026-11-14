/*
 * ============================================================================
 * Name        : PartialPayload.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-577. The rules for resuming a cut Share item: which items keep a partial file
 *               (an exact-build id, e.g. the ZIM UUID), where a resume starts, and whether the
 *               sender's reply continues the kept bytes. Pure JVM, unit-tested.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cut Share item keeps its bytes in a partial file named by the item's exact-build id, so a Retry,
 * or a new share of the same build, continues it with {@code Range: bytes=N-}. The sender decides if
 * it can resume (206) or must send the whole item (200); the box verifies the item before import.
 */
public final class PartialPayload {

    /** Staging-name prefix of every kept partial, so a run can find and sweep stale ones. */
    public static final String PREFIX = "partial-";

    private static final Pattern APP = Pattern.compile("[a-z0-9]{1,16}");
    private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d{1,19})-\\d{1,19}/\\d{1,19}");

    private PartialPayload() {
    }

    /**
     * The staging name of the partial for an item, or null when the item has no safe exact-build id
     * (then it is not kept: a cut item restarts at 0, as before).
     */
    public static String name(String app, String buildId) {
        if (app == null || buildId == null || !APP.matcher(app).matches()
                || !BUILD.matcher(buildId).matches()) {
            return null;
        }
        return PREFIX + app + "-" + buildId;
    }

    public static boolean isPartial(String storedName) {
        return storedName != null && storedName.startsWith(PREFIX);
    }

    /**
     * The byte to resume from: the kept length when it is not more than the item size, else 0 (a
     * longer file is not this item; start over). Equal to the size means nothing is left to fetch.
     */
    public static long resumeFrom(long kept, long size) {
        return kept > 0 && kept <= size ? kept : 0L;
    }

    /**
     * True when a failed item keeps its partial for a later resume: only an exact-file transfer that
     * was cut. A failed ingest means the box rejected the bytes, and a cancel means the user gave up.
     */
    public static boolean keepOnFailure(boolean resumable, boolean copied, boolean cancelled) {
        return resumable && !copied && !cancelled;
    }

    /** True when an exact-file copy holds the whole item, no more and no less. */
    public static boolean isComplete(long copied, long size) {
        return copied == size;
    }

    /**
     * Where the reply body starts in the item: 0 for a whole item (200), the requested byte for a 206
     * whose Content-Range starts there, else -1 (a reply that does not continue the kept bytes).
     */
    public static long bodyStart(int code, long requested, String contentRange) {
        if (code == 200) {
            return 0L;
        }
        if (code != 206 || contentRange == null) {
            return -1L;
        }
        Matcher m = CONTENT_RANGE.matcher(contentRange.trim());
        if (!m.matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(m.group(1)) == requested ? requested : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
