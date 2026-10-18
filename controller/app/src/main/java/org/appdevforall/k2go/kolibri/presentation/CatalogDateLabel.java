/*
 * ============================================================================
 * Name        : CatalogDateLabel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-476. One localized format for "catalog updated" dates.
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.presentation;

import android.content.Context;
import android.text.format.DateUtils;

final class CatalogDateLabel {

    private CatalogDateLabel() {
    }

    /** "17 oct 2026" or "17 oct 2026, 14:32" in the device's locale. */
    static String of(Context ctx, long ms, boolean withTime) {
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                | DateUtils.FORMAT_ABBREV_MONTH;
        if (withTime) {
            flags |= DateUtils.FORMAT_SHOW_TIME;
        } else {
            // A date-only header is a UTC calendar day; in the device's zone it can read as the day before.
            flags |= DateUtils.FORMAT_UTC;
        }
        return DateUtils.formatDateTime(ctx, ms, flags);
    }
}
