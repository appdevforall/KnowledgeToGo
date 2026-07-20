/*
 * ============================================================================
 * Name        : LibrarySize.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Approximate system-vs-content byte split of the installed IIAB
 *               library (ADFA-4780). v1 boundary: content = everything under
 *               <iiab>/library ; system = the rest of the rootfs (total − content).
 *               Uses lstat so the many symlinks in the Debian rootfs are skipped
 *               (never followed, never double-counted). Approximate by design —
 *               a per-module roster / build-time size index can refine it later.
 *               Must run off the main thread (walks the whole rootfs once).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.util.Locale;

public final class LibrarySize {

    private LibrarySize() {}

    public static final class Split {
        public final long systemBytes;
        public final long contentBytes;
        public Split(long systemBytes, long contentBytes) {
            this.systemBytes = systemBytes;
            this.contentBytes = contentBytes;
        }
        public long totalBytes() { return systemBytes + contentBytes; }
    }

    /** Walk the rootfs once (skipping symlinks). Off-main-thread only. */
    public static Split compute(File iiabRoot) {
        long total = du(iiabRoot);
        long content = du(new File(iiabRoot, "library"));
        long system = Math.max(0L, total - content);
        return new Split(system, content);
    }

    private static long du(File f) {
        if (f == null) return 0L;
        try {
            StructStat st = Os.lstat(f.getPath());
            if (OsConstants.S_ISLNK(st.st_mode)) return 0L;   // don't follow symlinks
            if (OsConstants.S_ISREG(st.st_mode)) return st.st_size;
            if (OsConstants.S_ISDIR(st.st_mode)) {
                long sum = 0L;
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) sum += du(k);
                return sum;
            }
        } catch (ErrnoException | SecurityException ignored) {
            // unreadable entry — count as 0
        }
        return 0L;
    }

    /** Compact size label: "1.7 GB" for >= 1 GB, else "820 MB". */
    public static String human(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb);
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.0f MB", mb);
    }
}
