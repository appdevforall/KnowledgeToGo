package org.appdevforall.k2go.install.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADFA-5228: turns a runrole's Ansible output stream into a determinate percent by matching each
 * {@code TASK [role : name]} line against a known, ordered list of that module's task names.
 *
 * <p><b>Why match by name, not by line index.</b> Ansible's raw task/line count drifts run-to-run:
 * {@code with_items} loops print a variable number of {@code item=} lines, {@code include_tasks}
 * pulls in dynamic files, and skipped tasks are omitted. But the set and order of a role's NAMED
 * tasks is stable. Progress is the furthest known task reached over the total, so a skipped or
 * unknown task never moves the bar backwards and a loop never over-counts.
 *
 * <p>Pure JVM, unit-tested. The Android side only feeds it lines and reads {@link #percent()}; the
 * ordered name list is loaded from a per-module asset (seeded from the upstream build logs).
 */
public final class RunroleProgress {

    /** Liftoff floor: the bar jumps here on the first sign of movement, before any known task, so it
     *  never sits dead at 0 while the runrole runs common/dependency tasks that aren't in the table. */
    public static final int LIFTOFF = 5;
    /** Ceiling while running: 95->100 is the finish, reached only via {@link #markComplete()}. */
    public static final int CEIL = 95;
    /** Warmup tasks (common/dependency tasks before the module's own) to reach {@link #LIFTOFF}. A
     *  heuristic, not derived from build logs (those run every role, so they don't model a single
     *  module's short warmup); the bar just needs to creep, not be exact. */
    public static final int WARMUP_TARGET = 10;

    private final Map<String, Integer> indexByName;
    private final int total;
    private int furthest = -1;
    private boolean complete = false;
    private boolean sawMovement = false;
    private int warmupTasks = 0;

    public RunroleProgress(List<String> orderedTaskNames) {
        this.indexByName = new HashMap<>();
        int i = 0;
        if (orderedTaskNames != null) {
            for (String raw : orderedTaskNames) {
                if (raw == null) continue;
                String name = raw.trim();
                if (name.isEmpty()) continue;
                if (!indexByName.containsKey(name)) indexByName.put(name, i); // first position wins
                i++;
            }
        }
        this.total = i;
    }

    /** Feed one line of Ansible output. Safe to call on every line. */
    public void observe(String line) {
        if (complete || line == null) return;
        sawMovement = true;                     // ADFA-5228: any output means the runrole is moving
        String name = taskName(line);
        if (name == null) return;
        Integer idx = indexByName.get(name);
        if (idx != null) {
            if (idx > furthest) furthest = idx;
        } else if (furthest < 0) {
            warmupTasks++;                      // a pre-module (common/dependency) task: creep the liftoff
        }
    }

    /** Mark the run finished (PLAY RECAP seen, or the process exited ok) so {@link #percent()} is 100. */
    public void markComplete() { complete = true; }

    /**
     * @return 0..100. {@code 0} when there is no table to match against (the caller should fall back
     *         to an indeterminate bar); {@code 100} once {@link #markComplete()} is called; otherwise
     *         the furthest known task reached over the total, clamped to 99 so it never reads done
     *         before it is.
     */
    public int percent() {
        if (complete) return 100;
        if (total == 0) return 0;                // no table -> caller stays indeterminate
        if (!sawMovement) return 0;              // nothing has run yet
        if (furthest < 0) {                      // moving, but no module task reached yet:
            // creep 1..LIFTOFF across the warmup so the bar isn't flat at the floor.
            int p = (int) Math.round(LIFTOFF * Math.min(1.0, (double) warmupTasks / WARMUP_TARGET));
            return Math.max(1, Math.min(LIFTOFF, p));
        }
        int done = furthest + 1;                 // entering task i means i tasks have been reached
        int pct = LIFTOFF + (int) Math.round(done * (double) (CEIL - LIFTOFF) / total);
        if (pct < LIFTOFF) pct = LIFTOFF;
        if (pct > CEIL) pct = CEIL;              // 95 until markComplete() -> 100
        return pct;
    }

    /** True when a match table is present; when false the caller should stay indeterminate. */
    public boolean hasTable() { return total > 0; }

    /** Number of known tasks in the table (0 when none). */
    public int total() { return total; }

    /** How many known tasks have been reached so far (0..total) — the ETA uses this to know which
     *  tasks remain. */
    public int reached() { return furthest + 1; }

    /**
     * Extract the task name from a {@code TASK [role : name] ***} line, or {@code null} if the line
     * isn't a task header. A role-less pre-task ({@code TASK [Gather facts] ***}) yields its bare
     * text, which simply won't be in any module table. Matches how the asset tables were extracted
     * (name = the text after {@code " : "}, up to the first {@code ']'}).
     */
    public static String taskName(String line) {
        int open = line.indexOf("TASK [");
        if (open < 0) return null;
        int start = open + 6;
        int close = line.indexOf(']', start);
        if (close < 0) return null;
        String inside = line.substring(start, close);   // "role : name"  (or "name" for role-less)
        int sep = inside.indexOf(" : ");
        return (sep >= 0 ? inside.substring(sep + 3) : inside).trim();
    }
}
