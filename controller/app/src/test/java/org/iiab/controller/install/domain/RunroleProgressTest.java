package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Unit tests for {@link RunroleProgress} — the ADFA-5228 determinate signal: a 0..5% warmup creep
 * (common/dependency tasks), the module's known tasks over 5..95%, and 100% only on completion.
 */
public class RunroleProgressTest {

    private static String task(String role, String name) {
        return "TASK [" + role + " : " + name + "] ***";
    }

    private RunroleProgress four() {
        return new RunroleProgress(Arrays.asList("a", "b", "c", "d"));   // span 90 over 4 -> 28/50/73/95
    }

    @Test
    public void zeroBeforeAnyMovement() {
        assertEquals(0, four().percent());
    }

    @Test
    public void firstMovementCreepsToOne() {
        RunroleProgress p = four();
        p.observe("ok: [127.0.0.1]");        // output, but not a task header -> just "moving"
        assertEquals(1, p.percent());
    }

    @Test
    public void warmupTasksCreepTowardLiftoff() {
        RunroleProgress p = four();
        for (int i = 0; i < 4; i++) p.observe(task("m", "warmup " + i));   // 4/10 of the way
        assertEquals(2, p.percent());                                      // round(5 * 4/10) = 2
        for (int i = 0; i < 10; i++) p.observe(task("m", "warmup x" + i)); // well past target
        assertEquals(RunroleProgress.LIFTOFF, p.percent());                // capped at 5
    }

    @Test
    public void firstKnownTaskLeavesWarmupForTheBand() {
        RunroleProgress p = four();
        p.observe(task("m", "warmup 1"));
        p.observe(task("m", "warmup 2"));
        assertTrue(p.percent() < RunroleProgress.LIFTOFF);   // still warming up
        p.observe(task("m", "a"));                            // first module task
        assertEquals(28, p.percent());                        // jumps into the 5..95 band
    }

    @Test
    public void advancesAcrossTheBand() {
        RunroleProgress p = four();
        p.observe(task("m", "a"));
        assertEquals(28, p.percent());
        p.observe(task("m", "b"));
        assertEquals(50, p.percent());
        p.observe(task("m", "c"));
        assertEquals(73, p.percent());
    }

    @Test
    public void lastTaskCapsAtCeilingUntilComplete() {
        RunroleProgress p = four();
        p.observe(task("m", "d"));
        assertEquals(RunroleProgress.CEIL, p.percent());   // 95, not 100
        p.markComplete();
        assertEquals(100, p.percent());
    }

    @Test
    public void skippedTaskJumpsToFurthestReached() {
        RunroleProgress p = four();
        p.observe(task("m", "a"));
        p.observe(task("m", "c"));   // b skipped
        assertEquals(73, p.percent());
    }

    @Test
    public void neverGoesBackwards() {
        RunroleProgress p = four();
        p.observe(task("m", "c"));
        assertEquals(73, p.percent());
        p.observe(task("m", "a"));   // a late/earlier task must not lower the bar
        assertEquals(73, p.percent());
    }

    @Test
    public void emptyTableIsIndeterminate() {
        RunroleProgress p = new RunroleProgress(Collections.<String>emptyList());
        assertFalse(p.hasTable());
        assertEquals(0, p.percent());
        p.observe(task("m", "a"));
        assertEquals(0, p.percent());   // no table -> stays 0 (caller keeps the spinner)
    }

    @Test
    public void taskNameExtraction() {
        assertEquals("Set first_run flag", RunroleProgress.taskName("TASK [0-init : Set first_run flag] ***"));
        assertEquals("Gather facts", RunroleProgress.taskName("TASK [Gather facts] *****"));
        assertEquals(null, RunroleProgress.taskName("PLAY RECAP *********"));
        assertEquals(null, RunroleProgress.taskName("ok: [127.0.0.1]"));
    }

    @Test
    public void hasTableTrueWhenSeeded() {
        assertTrue(four().hasTable());
        assertEquals(4, four().total());
    }
}
