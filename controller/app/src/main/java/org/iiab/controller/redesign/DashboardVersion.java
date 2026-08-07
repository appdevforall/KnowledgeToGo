/*
 * ============================================================================
 * Name        : DashboardVersion.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5011. Reads the INSTALLED dash-node version straight from the rootfs on disk
 *               (installed-rootfs/iiab/library/dashboard/package.json — where install_iiaboa_dashboard
 *               places it and where dash-node itself reads it via process.cwd()). This is authoritative
 *               and always available: no network and no proot, so it works even when the server is
 *               stopped or the running build predates the /system/version REST endpoint (which only
 *               ships in newer builds — the reason the card showed no version before a first rebuild).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class DashboardVersion {
    private DashboardVersion() {}

    /** ADFA-5051: true when {@code version} (x.y.z, any suffix ignored) is >= major.minor.patch.
     *  A null/unparseable version returns false, so callers default to the safe (proot) path. */
    public static boolean atLeast(@Nullable String version, int major, int minor, int patch) {
        if (version == null) return false;
        String core = version.split("[-+]", 2)[0];   // drop any "-beta"/"+build" suffix
        String[] p = core.split("\\.");
        int[] want = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            int have = 0;
            if (i < p.length) { try { have = Integer.parseInt(p[i].trim()); } catch (NumberFormatException e) { have = 0; } }
            if (have != want[i]) return have > want[i];
        }
        return true;   // exactly equal
    }

    /** Installed dash-node version from the rootfs package.json, or null if not found/parseable. */
    @Nullable
    public static String installed(@NonNull Context ctx) {
        File pkg = new File(ctx.getFilesDir(),
                "rootfs/installed-rootfs/iiab/library/dashboard/package.json");
        if (!pkg.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(pkg))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String v = new JSONObject(sb.toString()).optString("version", "");
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }
}
