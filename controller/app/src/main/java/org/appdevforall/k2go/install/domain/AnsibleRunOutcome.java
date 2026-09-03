/*
 * File        : AnsibleRunOutcome.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4435 — pure decision for whether a runrole/Ansible execution failed.
 *               Ansible can print its failure to stdout yet still exit 0 (e.g. the /dev/shm
 *               multiprocessing crash), so the verdict considers the output as well as the
 *               exit code. No Android dependencies -> unit-testable on the JVM.
 */
package org.appdevforall.k2go.install.domain;

public final class AnsibleRunOutcome {

    private boolean sawError = false;

    /** Feed each output line as it streams from the container. */
    public void observe(String line) {
        if (line == null) return;
        if (line.contains("[ERROR]")
                || line.contains("Unable to use multiprocessing")
                || line.contains("HEARTBEAT SESSION STOPPED")) {
            sawError = true;
        }
    }

    /** True if the run failed: a non-zero exit OR an error seen in the output. */
    public boolean failed(int exitCode) {
        return exitCode != 0 || sawError;
    }
}
