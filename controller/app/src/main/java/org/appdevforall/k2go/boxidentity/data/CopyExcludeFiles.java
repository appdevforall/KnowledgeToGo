package org.appdevforall.k2go.boxidentity.data;

import android.content.Context;

import org.appdevforall.k2go.boxidentity.domain.CopyExcludes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Writes the {@link CopyExcludes} list to the cache dir, in the form each copy tool reads. */
public final class CopyExcludeFiles {

    private CopyExcludeFiles() {
    }

    /** For the rsync daemon that serves the box root (Clone send). */
    public static File rsync(Context ctx) throws IOException {
        return write(ctx, "box-copy-exclude.rsync", CopyExcludes.forRsync(asset(ctx)));
    }

    /** For tar, where {@code memberPrefix} is the archive name of the box root (Backup). */
    public static File tar(Context ctx, String memberPrefix) throws IOException {
        return write(ctx, "box-copy-exclude.tar", CopyExcludes.forTar(asset(ctx), memberPrefix));
    }

    private static String asset(Context ctx) throws IOException {
        try (InputStream in = ctx.getAssets().open(CopyExcludes.ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static File write(Context ctx, String name, String content) throws IOException {
        File f = new File(ctx.getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }
}
