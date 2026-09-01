/*
 * ============================================================================
 * Name        : EnvironmentProcessMatcher.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5061. Pure rule: is this /proc cmdline the long-running
 *               environment proot for our rootfs?
 * ============================================================================
 */
package org.appdevforall.k2go.env.domain;

/**
 * Decides whether a process is <em>the environment</em>.
 *
 * <p>The app had exactly one runtime signal about the box — an HTTP ping — so "the services were
 * stopped and the proot is still running" and "everything is down" were the same observation. The
 * consequence was not cosmetic: the retry path reads "not answering" as "nothing is running", and
 * starting then stacks a second proot over a live one, which is the collision ADR-4832 documents.
 *
 * <p>The environment proot is a child of this app, so the host's own {@code /proc} answers the
 * question with no container, no ptrace and nothing mutated. That matters: querying through a
 * transient proot would rewrite the shared loader symlinks and re-bind {@code /tmp} and
 * {@code /dev/shm} that the live one depends on, and would learn nothing extra, because proot
 * binds the host's {@code /proc} anyway.
 *
 * <p>Pure and testable, mirroring {@code RsyncProcessMatcher}, which solves the same shape for
 * lingering rsync children. The {@code /proc} walk and any signal stay in the Android layer.
 */
public final class EnvironmentProcessMatcher {

    /**
     * The tail of the environment's command line.
     *
     * <p>{@code ServerController.startEnvironment} runs {@code pdsm start && tail -f /dev/null}:
     * the {@code tail} is what keeps the proot alive after the services are up, and it is what
     * makes this process <em>the environment</em> rather than a piece of work. Matching on it is
     * the difference between killing an orphaned environment — which is the recovery — and
     * killing a runrole mid-install, which would be the disaster.
     */
    private static final String ENVIRONMENT_MARKER = "tail -f /dev/null";

    private EnvironmentProcessMatcher() {
    }

    /**
     * True when {@code cmdline} is our environment proot for {@code rootfsPath}.
     *
     * <p>Both halves are required. The rootfs path lives under the app's private storage, so it
     * cannot match another app; the marker separates the environment from every other proot we
     * launch against the same rootfs — an install runrole, a backup, a clone.
     *
     * @param cmdline    a {@code /proc/<pid>/cmdline}, NUL bytes already replaced by spaces
     * @param rootfsPath the canonical path we pass to proot's {@code -r}
     */
    public static boolean isOurEnvironment(String cmdline, String rootfsPath) {
        if (cmdline == null || rootfsPath == null || cmdline.isEmpty() || rootfsPath.isEmpty()) {
            return false;
        }
        return cmdline.contains(rootfsPath) && cmdline.contains(ENVIRONMENT_MARKER);
    }
}
