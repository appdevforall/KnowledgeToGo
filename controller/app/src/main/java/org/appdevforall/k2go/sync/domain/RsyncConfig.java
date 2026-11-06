/*
 * ============================================================================
 * Name        : RsyncConfig.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain builders for the rsync transport's configuration: the
 *               rsyncd.conf daemon file, the rsync:// client URL, and the argv
 *               for the server / client / dry-run invocations. Pure (no
 *               android.*, no I/O, no process launch), so it is unit-testable on
 *               a plain JVM and clonable by an external host (Share-export, S14
 *               step 1). The binary path and credentials are passed in by the
 *               caller; this class only assembles strings/argv. Behaviour is
 *               unchanged from the previous inline construction in RsyncManager.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RsyncConfig {

    private RsyncConfig() {
    }

    /**
     * Builds the rsyncd.conf served by {@code rsync --daemon}. Callers must have
     * validated {@code user}/{@code sharePath} (see {@link SyncCredentialValidator})
     * before passing them, since they are interpolated verbatim.
     */
    public static String buildDaemonConf(String pidPath, String lockPath, int port,
                                         String moduleName, String sharePath,
                                         String user, String secretsPath) {
        return buildDaemonConf(pidPath, lockPath, port, moduleName, sharePath, user, secretsPath, null);
    }

    /**
     * Same, plus a daemon-side exclude file (may be null). The daemon enforces it, so the
     * receiver never gets those files, whatever its own options are.
     */
    public static String buildDaemonConf(String pidPath, String lockPath, int port,
                                         String moduleName, String sharePath,
                                         String user, String secretsPath, String excludeFromPath) {
        return "pid file = " + pidPath + "\n" +
                "lock file = " + lockPath + "\n" +
                "port = " + port + "\n" +
                "use chroot = no\n" +
                "log file = /dev/null\n" +
                "reverse lookup = no\n" +
                "\n" +
                "[" + moduleName + "]\n" +
                "path = " + sharePath + "\n" +
                "comment = IIAB Peer-to-Peer Sync\n" +
                "read only = yes\n" +
                "timeout = 300\n" +
                "max connections = 3\n" +
                "auth users = " + user + "\n" +
                "secrets file = " + secretsPath + "\n" +
                (excludeFromPath != null ? "exclude from = " + excludeFromPath + "\n" : "");
    }

    /** Builds the {@code rsync://user@host:port/module/} client URL. */
    public static String buildRemoteUrl(String user, String hostIp, int port, String moduleName) {
        return "rsync://" + user + "@" + hostIp + ":" + port + "/" + moduleName + "/";
    }

    /** argv for the daemon (server) process. */
    public static List<String> serverArgs(String rsyncBinPath, String configPath) {
        return new ArrayList<>(Arrays.asList(
                rsyncBinPath,
                "--daemon",
                "--no-detach",
                "--config=" + configPath));
    }

    /** argv for the pull (client) transfer. */
    public static List<String> clientArgs(String rsyncBinPath, String passFilePath,
                                          String remoteUrl, String destDir) {
        return new ArrayList<>(Arrays.asList(
                rsyncBinPath,
                "-av",
                "--delete",
                "--info=progress2",
                "--partial",
                "--password-file=" + passFilePath,
                remoteUrl,
                destDir));
    }

    /**
     * Adds a client-side exclude file to a pull argv ({@link #clientArgs} or {@link #dryRunArgs}).
     * The client sends the rules to the sender, so a sender without its own list does not send
     * those files either. --delete-excluded also removes them from the destination.
     */
    public static List<String> withExcludes(List<String> pullArgs, String excludeFromPath) {
        List<String> a = new ArrayList<>(pullArgs);
        int src = a.size() - 2;   // the remote URL, then the destination
        a.add(src, "--delete-excluded");
        a.add(src, "--exclude-from=" + excludeFromPath);
        return a;
    }

    /** argv for the dry-run transfer-size estimate. */
    public static List<String> dryRunArgs(String rsyncBinPath, String passFilePath,
                                          String remoteUrl, String destDir) {
        return new ArrayList<>(Arrays.asList(
                rsyncBinPath,
                "-av",
                "--delete",
                "--dry-run",
                "--stats",
                "--password-file=" + passFilePath,
                remoteUrl,
                destDir));
    }
}
