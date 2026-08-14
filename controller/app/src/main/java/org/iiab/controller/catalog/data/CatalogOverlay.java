/*
 * ============================================================================
 * Name        : CatalogOverlay.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Where a pulled catalog lands on the
 *               device. The refresh worker writes the overlay here and the
 *               catalog source reads it (when present and newer) in place of the
 *               APK-bundled asset. Same path convention on both sides.
 * ============================================================================
 */
package org.iiab.controller.catalog.data;

import android.content.Context;

import java.io.File;

public final class CatalogOverlay {

    private static final String DIR = "catalogs";

    private CatalogOverlay() {
    }

    /** {@code filesDir/catalogs}, created if missing. */
    public static File dir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /** The overlay file for a catalog whose bundled asset is named {@code basename}. */
    public static File file(Context ctx, String basename) {
        return new File(dir(ctx), basename);
    }
}
