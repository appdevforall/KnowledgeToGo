/*
 * ============================================================================
 * Name        : ProcessRunner.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Small helper to run an external process safely: it always
 *               drains the output so the child cannot deadlock on a full pipe
 *               buffer, and returns the exit code instead of letting callers
 *               swallow failures. Addresses tech-debt item D12.
 * ============================================================================
 */
package org.iiab.controller.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Runs an external command and <strong>always reads its output</strong>.
 *
 * <p>Calling {@code Runtime.exec(...).waitFor()} without reading the child's
 * stdout/stderr risks a deadlock: once the OS pipe buffer (~64&nbsp;KB) fills,
 * the child blocks writing while the parent blocks in {@code waitFor()} — see
 * tech-debt item <b>D12</b>. This helper merges stderr into stdout
 * ({@link ProcessBuilder#redirectErrorStream(boolean)}) so a single read drains
 * everything, then returns the exit code and captured output so callers can log
 * or react to failures instead of ignoring them.
 *
 * <p>Process I/O only (no {@code android.*}); call it off the main thread.
 */
public final class ProcessRunner {

    /** Outcome of a process run: its exit code and combined stdout+stderr. */
    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private ProcessRunner() {
        // Static utility; not instantiable.
    }

    /**
     * Start {@code command}, drain its merged stdout+stderr to completion, wait
     * for it to exit, and return the exit code together with the captured output.
     */
    public static Result run(String[] command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // merge stderr into stdout: one drain can't deadlock
        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }

        int exitCode = process.waitFor();
        return new Result(exitCode, out.toString());
    }
}
