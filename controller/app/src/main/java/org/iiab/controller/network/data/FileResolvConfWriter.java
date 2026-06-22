/*
 * ============================================================================
 * Name        : FileResolvConfWriter.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Writes resolv.conf (+ minimal hosts) into a rootfs; migrates the #25 helper logic.
 * ============================================================================
 */
package org.iiab.controller.network.data;

import android.util.Log;

import org.iiab.controller.network.domain.DnsConfig;
import org.iiab.controller.network.domain.ResolvConfWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link ResolvConfWriter} that writes {@code etc/resolv.conf} (and a minimal
 * {@code etc/hosts} if absent) into a guest rootfs. This is the single home for the
 * DNS-write logic previously duplicated in {@code DeployFragment}/{@code MainActivity}.
 * Guards on {@code etc/} existence and never throws.
 */
public final class FileResolvConfWriter implements ResolvConfWriter {

    private static final String TAG = "IIAB-DNS";

    @Override
    public boolean write(DnsConfig config, File rootfsDir) {
        try {
            File etc = new File(rootfsDir, "etc");
            if (!etc.isDirectory()) {
                Log.w(TAG, "resolv.conf skipped: missing " + etc.getAbsolutePath());
                return false;
            }
            File resolv = new File(etc, "resolv.conf");
            if (resolv.exists() && !resolv.delete()) {
                Log.w(TAG, "could not remove old resolv.conf at " + resolv.getAbsolutePath());
            }
            try (FileOutputStream fos = new FileOutputStream(resolv)) {
                fos.write(config.toResolvConf().getBytes(StandardCharsets.UTF_8));
            }
            File hosts = new File(etc, "hosts");
            if (!hosts.exists()) {
                try (FileOutputStream fos = new FileOutputStream(hosts)) {
                    fos.write("127.0.0.1 localhost\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            Log.i(TAG, "wrote resolv.conf (" + config + ") under " + etc.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "FileResolvConfWriter.write failed", e);
            return false;
        }
    }
}
