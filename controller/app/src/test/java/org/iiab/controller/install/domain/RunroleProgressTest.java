package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Unit tests for {@link RunroleProgress} — the ADFA-5228 determinate-progress signal derived from
 * matching Ansible {@code TASK [role : name]} lines against an ordered task table.
 */
public class RunroleProgressTest {

    private static String task(String role, String name) {
        return "TASK [" + role + " : " + name + "] ***";
    }

    private RunroleProgress four() {
        return new RunroleProgress(Arrays.asList("a", "b", "c", "d"));
    }

    @Test
    public void startsAtZeroAndAdvancesByFurthestTask() {
        RunroleProgress p = four();
        assertEquals(0, p.percent());
        p.observe(task("m", "a"));
        assertEquals(25, p.percent());
        p.observe(task("m", "b"));
        assertEquals(50, p.percent());
        p.observe(task("m", "c"));
        assertEquals(75, p.percent());
    }

    @Test
    public void lastTaskStaysAt99UntilComplete() {
        RunroleProgress p = four();
        p.observe(task("m", "d"));   // entered the last known task, but the run isn't done
        assertEquals(99, p.percent());
        p.markComplete();
        assertEquals(100, p.percent());
    }

    @Test
    public void skippedTaskDoesNotStall_takesFurthestReached() {
        RunroleProgress p = four();
        p.observe(task("m", "a"));
        p.observe(task("m", "c"));   // b was skipped -> jump to c, not stuck at a
        assertEquals(75, p.percent());
    }

    @Test
    public void outOfOrderOrRepeatNeverGoesBackwards() {
        RunroleProgress p = four();
        p.observe(task("m", "c"));
        assertEquals(75, p.percent());
        p.observe(task("m", "a"));   // a late/earlier task must not lower the bar
        assertEquals(75, p.percent());
    }

    @Test
    public void unknownAndNonTaskLinesAreIgnored() {
        RunroleProgress p = four();
        p.observe("changed: [127.0.0.1] => (item=something)");
        p.observe("skipping: [127.0.0.1]");
        p.observe(task("m", "not-in-table"));
        p.observe(task("m", "Gather facts")); // role-less style still just misses the table
        assertEquals(0, p.percent());
    }

    @Test
    public void emptyTableIsIndeterminate() {
        RunroleProgress p = new RunroleProgress(Collections.<String>emptyList());
        assertFalse(p.hasTable());
        assertEquals(0, p.percent());
        p.observe(task("m", "a"));
        assertEquals(0, p.percent());
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
