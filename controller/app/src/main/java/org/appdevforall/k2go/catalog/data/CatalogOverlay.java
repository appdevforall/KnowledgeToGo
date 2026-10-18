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
package org.appdevforall.k2go.catalog.data;

import android.content.Context;

import org.appdevforall.k2go.catalog.domain.CatalogFreshness;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

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

    /**
     * K2GO-476: whether to read the pulled overlay rather than the APK asset: it exists and is
     * not older than the asset (see {@link CatalogFreshness#overlayIsCurrent}).
     */
    public static boolean preferOverlay(Context ctx, String basename, String assetName) {
        File overlay = file(ctx, basename);
        if (!overlay.exists()) {
            return false;
        }
        String overlayGenerated;
        try (InputStream in = new FileInputStream(overlay)) {
            overlayGenerated = generatedOf(in);
        } catch (IOException e) {
            return false;
        }
        String assetGenerated;
        try (InputStream in = ctx.getAssets().open(assetName)) {
            assetGenerated = generatedOf(in);
        } catch (IOException e) {
            assetGenerated = "";
        }
        return CatalogFreshness.overlayIsCurrent(overlayGenerated, assetGenerated);
    }

    /** The {@code generated} field of a catalog file's first-line header, or "" when absent. */
    public static String generatedOf(InputStream in) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(gzipAware(in), StandardCharsets.UTF_8));
            String first = r.readLine();
            if (first == null) {
                return "";
            }
            JSONObject o = new JSONObject(first.trim());
            return o.has("id") ? "" : o.optString("generated", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Reads {@code in} whether it is gzip or plain: an R2 overlay is a real gzip, while AGP strips
     * the {@code .gz} from an APK asset at merge time. The 2-byte gzip signature decides. The
     * caller closes the returned stream, which closes {@code in}.
     */
    public static InputStream gzipAware(InputStream in) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(in, 2);
        byte[] sig = new byte[2];
        int n = 0;
        while (n < 2) {
            int r = pb.read(sig, n, 2 - n);
            if (r < 0) {
                break;
            }
            n += r;
        }
        boolean gzip = n == 2 && (sig[0] & 0xff) == 0x1f && (sig[1] & 0xff) == 0x8b;
        if (n > 0) {
            pb.unread(sig, 0, n);
        }
        return gzip ? new GZIPInputStream(pb) : pb;
    }
}
