package org.iiab.controller.install.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AnsibleRunOutcomeTest {

    @Test public void cleanSuccess_isNotFailed() {
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("TASK [base : something] ok");
        o.observe("PLAY RECAP ok=5 changed=2 failed=0");
        assertFalse(o.failed(0));
    }

    @Test public void nonZeroExit_isFailed() {
        assertTrue(new AnsibleRunOutcome().failed(1));
    }

    @Test public void phantomKill_exit137_isFailed() {
        // Android 12+ phantom-process killer SIGKILLs container children -> exit 137.
        // The service (ADFA-4476 slice 3) treats it as a per-module failure (revert + report).
        assertTrue(new AnsibleRunOutcome().failed(137));
    }

    @Test public void multiprocessingCrash_withExitZero_isFailed() {
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("ERROR! Unable to use multiprocessing, see stderr (lack of access to /dev/shm)");
        assertTrue(o.failed(0));
    }

    @Test public void ansibleError_withExitZero_isFailed() {
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("fatal: [localhost]: FAILED! => something");
        o.observe("[ERROR]: Task failed");
        assertTrue(o.failed(0));
    }

    @Test public void heartbeatStopped_withExitZero_isFailed() {
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("HEARTBEAT SESSION STOPPED");
        assertTrue(o.failed(0));
    }

    @Test public void nullLine_isIgnored() {
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe(null);
        assertFalse(o.failed(0));
    }
}
