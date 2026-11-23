/*
 * ============================================================================
 * Name        : ModuleQueueStateTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-593. A user Cancel ends the batch with the module in the
 *               failed list (Retry stays offered) and marked as canceled, so
 *               the UI can tell a user stop from an error.
 * ============================================================================
 */
package org.appdevforall.k2go.install.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ModuleQueueStateTest {

    @Test
    public void canceledModuleIsFailedAndCanceled() {
        ModuleQueueState st = ModuleQueueState.done(Collections.singletonList("forgejo"), "forgejo");
        assertTrue(st.didFail("forgejo"));
        assertTrue(st.wasCanceled("forgejo"));
        assertTrue(st.hasFailures());
    }

    @Test
    public void realFailureIsNotCanceled() {
        ModuleQueueState st = ModuleQueueState.done(Collections.singletonList("forgejo"));
        assertTrue(st.didFail("forgejo"));
        assertFalse(st.wasCanceled("forgejo"));
        assertNull(st.canceledModule);
    }

    @Test
    public void onlyTheCanceledModuleReadsCanceled() {
        ModuleQueueState st = ModuleQueueState.done(Arrays.asList("matomo", "forgejo"), "forgejo");
        assertTrue(st.didFail("matomo"));
        assertFalse(st.wasCanceled("matomo"));
        assertTrue(st.wasCanceled("forgejo"));
    }

    @Test
    public void canceledOnlyMeansSomethingOnDone() {
        assertFalse(ModuleQueueState.running("forgejo", 0).wasCanceled("forgejo"));
        assertFalse(ModuleQueueState.idle().wasCanceled("forgejo"));
    }

    @Test
    public void sequenceKeepsTheCanceledModule() {
        ModuleQueueState st = ModuleQueueState.done(Collections.singletonList("forgejo"), "forgejo").withSeq(7L);
        assertEquals(7L, st.seq);
        assertTrue(st.wasCanceled("forgejo"));
    }
}
