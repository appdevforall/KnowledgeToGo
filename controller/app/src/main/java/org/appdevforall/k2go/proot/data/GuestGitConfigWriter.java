/*
 * ============================================================================
 * Name        : GuestGitConfigWriter.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Brings the rootfs /etc/gitconfig in line with GuestGitConfig (K2GO-558).
 * ============================================================================
 */
package org.appdevforall.k2go.proot.data;

import android.util.Log;

import org.appdevforall.k2go.proot.domain.GuestGitConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class GuestGitConfigWriter {

    private static final String TAG = "IIAB-GitConfig";

    private GuestGitConfigWriter() {}

    /**
     * No-op when the settings are already in effect. A temp file + rename, so a kill mid-write
     * never leaves a broken gitconfig (git refuses to run with one). Synchronized because proot
     * launches can overlap and share the temp file. Never throws.
     */
    public static synchronized void ensure(File rootfsDir) {
        try {
            File etc = new File(rootfsDir, "etc");
            if (!etc.isDirectory()) {
                return;
            }
            File conf = new File(etc, "gitconfig");
            byte[] existing = conf.isFile() ? read(conf) : new byte[0];
            String add = GuestGitConfig.toAppend(new String(existing, StandardCharsets.UTF_8));
            if (add.isEmpty()) {
                return;
            }
            File tmp = new File(etc, "gitconfig.k2go-tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(existing);   // the original bytes, unchanged
                fos.write(add.getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(conf)) {
                tmp.delete();
                Log.w(TAG, "could not replace " + conf);
                return;
            }
            Log.i(TAG, "git settings for proot added to " + conf);
        } catch (Exception e) {
            Log.w(TAG, "gitconfig update skipped", e);
        }
    }

    private static byte[] read(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) {
                buf.write(b, 0, n);
            }
            return buf.toByteArray();
        }
    }
}
