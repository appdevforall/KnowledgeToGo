/*
 * File        : AnsibleRunOutcome.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4435 / K2GO-450 - pure decision for whether a runrole/Ansible execution
 *               failed. The PLAY RECAP (per-host "... unreachable=N failed=M ...") is Ansible's
 *               authoritative verdict, so when a recap is present it decides: an IGNORED error
 *               (ignore_errors -> counted in ignored=, not failed=) prints [ERROR]/fatal lines but
 *               is NOT a failure. Only when no recap appears (Ansible died before it could
 *               summarize, e.g. the /dev/shm multiprocessing crash) do the [ERROR]/crash
 *               signatures stand in. A non-zero process exit is always a failure.
 *               No Android dependencies -> unit-testable on the JVM.
 */
package org.appdevforall.k2go.install.domain;

public final class AnsibleRunOutcome {

    // Pre-recap failure hints: used ONLY when no PLAY RECAP was seen (Ansible crashed before it
    // could summarize). When a recap IS present these are ignored, because Ansible prints [ERROR]
    // and fatal lines for ignored errors too, which are not failures.
    private boolean sawError = false;
    private boolean sawRecap = false;        // a PLAY RECAP host-summary line appeared
    private boolean recapFailure = false;    // a recap host line reported failed>0 or unreachable>0

    /** Feed each output line as it streams from the container. */
    public void observe(String line) {
        if (line == null) return;
        if (line.contains("[ERROR]")
                || line.contains("Unable to use multiprocessing")
                || line.contains("HEARTBEAT SESSION STOPPED")) {
            sawError = true;
        }
        // A PLAY RECAP host summary carries BOTH "unreachable=" and "failed=" (every real recap line
        // does); that pair is what distinguishes it from a stray task line that happens to contain one.
        int failed = count(line, "failed=");
        int unreachable = count(line, "unreachable=");
        if (failed >= 0 && unreachable >= 0) {
            sawRecap = true;
            if (failed > 0 || unreachable > 0) recapFailure = true;
        }
    }

    /** True if the run failed. A non-zero exit always fails; otherwise trust the PLAY RECAP when
     *  present, and fall back to the [ERROR]/crash signatures only when no recap was emitted. */
    public boolean failed(int exitCode) {
        if (exitCode != 0) return true;
        if (sawRecap) return recapFailure;
        return sawError;
    }

    /** The non-negative integer immediately after {@code key} in {@code line}, or -1 if absent. */
    private static int count(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return -1;
        int start = i + key.length();
        int j = start;
        while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
        if (j == start) return -1;
        try {
            return Integer.parseInt(line.substring(start, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
