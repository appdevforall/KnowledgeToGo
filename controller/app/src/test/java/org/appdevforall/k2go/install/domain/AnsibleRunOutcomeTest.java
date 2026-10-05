package org.appdevforall.k2go.install.domain;

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

    @Test public void ignoredError_withCleanRecap_isNotFailed() {
        // K2GO-450: the forgejo role has an ignore_errors task that prints [ERROR]/fatal, but the
        // PLAY RECAP is clean (failed=0, unreachable=0, ignored=1). An ignored error is not a
        // failure: with a recap present it is authoritative, so the run must NOT be marked failed.
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("fatal: [127.0.0.1]: FAILED! => {\"msg\": \"something\"} ...ignoring");
        o.observe("[ERROR]: an ignored task error");
        o.observe("127.0.0.1 : ok=338 changed=20 unreachable=0 failed=0 skipped=27 rescued=0 ignored=1");
        assertFalse(o.failed(0));
    }

    @Test public void realFailureRecap_isFailed() {
        // A genuinely failed task increments failed= in the recap -> failure (even on a quirky exit 0).
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("127.0.0.1 : ok=10 changed=3 unreachable=0 failed=2 skipped=1 rescued=0 ignored=0");
        assertTrue(o.failed(0));
    }

    @Test public void unreachableHost_isFailed() {
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("127.0.0.1 : ok=1 changed=0 unreachable=1 failed=0 skipped=0 rescued=0 ignored=0");
        assertTrue(o.failed(0));
    }

    @Test public void crashSignatureAfterCleanRecap_isStillFailed() {
        // K2GO-450: IIAB emits several intermediate PLAY RECAPs, so a hard crash can follow an early
        // clean recap. A clean recap only overrides the softer [ERROR], never a crash signature.
        AnsibleRunOutcome o = new AnsibleRunOutcome();
        o.observe("127.0.0.1 : ok=5 changed=2 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0");
        o.observe("ERROR! Unable to use multiprocessing, see stderr (lack of access to /dev/shm)");
        assertTrue(o.failed(0));
    }
}
