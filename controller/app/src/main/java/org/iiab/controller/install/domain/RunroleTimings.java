package org.iiab.controller.install.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * ADFA-5228: measures how long each runrole task takes on THIS device, so the ETA learns per-device
 * instead of trusting build-log times (which vary by hardware). Pure JVM, unit-tested.
 *
 * <p>Fed one event per {@code TASK [role : name]} line: the previous task's duration is closed when
 * the next task starts (a task lasts from its header to the next). Call {@link #finish(long)} when
 * the run ends to close the last task. Durations are keyed by task name (matching the tables), and a
 * repeated name keeps the last measurement.
 */
public final class RunroleTimings {

    private final Map<String, Long> durationMs = new HashMap<>();
    private String currentTask = null;
    private long currentStartMs = 0L;

    /** A task header was seen at {@code nowMs}. Closes the previous task's duration. */
    public void onTask(String name, long nowMs) {
        if (name == null) return;
        if (currentTask != null) {
            durationMs.put(currentTask, Math.max(0L, nowMs - currentStartMs));
        }
        currentTask = name;
        currentStartMs = nowMs;
    }

    /** The run ended at {@code nowMs}. Closes the final task's duration. */
    public void finish(long nowMs) {
        if (currentTask != null) {
            durationMs.put(currentTask, Math.max(0L, nowMs - currentStartMs));
            currentTask = null;
        }
    }

    /** Measured durations (ms) for the tasks that completed this run, keyed by task name. */
    public Map<String, Long> durationsMs() {
        return new HashMap<>(durationMs);
    }
}
