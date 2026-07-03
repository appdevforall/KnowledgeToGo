/*
 * ============================================================================
 * Name        : RsyncProcessMatcher.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule for ADFA-4539. The rsync daemon runs with
 *               --daemon --no-detach and forks a child per connection; killing
 *               only the parent Process leaves the connection child streaming
 *               the transfer (it reparents to init). To stop for real we sweep
 *               /proc and kill every process that is one of ours, identified by
 *               our unique librsync.so path in its cmdline. This class is the
 *               pure, testable decision "is this cmdline one of our rsync
 *               processes?"; the /proc walk and the SIGKILL stay in the Android
 *               layer. No android.* here.
 * ============================================================================
 */
package org.iiab.controller.sync.domain;

public final class RsyncProcessMatcher {

    private RsyncProcessMatcher() {
    }

    /**
     * True when {@code cmdline} (a /proc/<pid>/cmdline, NUL bytes already turned
     * into spaces or left as-is) belongs to one of our rsync processes, i.e. it
     * was launched from our app-private {@code rsyncBinPath}. The path is unique
     * to this app (its nativeLibraryDir), so this both matches all our rsync
     * processes (parent daemon + per-connection children) and cannot match
     * another app's or the system's processes.
     *
     * @param cmdline      raw cmdline of a candidate process (may be null/empty)
     * @param rsyncBinPath absolute path of our librsync.so (may be null/empty)
     */
    public static boolean isOurRsyncProcess(String cmdline, String rsyncBinPath) {
        if (cmdline == null || rsyncBinPath == null) {
            return false;
        }
        if (cmdline.isEmpty() || rsyncBinPath.isEmpty()) {
            return false;
        }
        return cmdline.contains(rsyncBinPath);
    }
}
