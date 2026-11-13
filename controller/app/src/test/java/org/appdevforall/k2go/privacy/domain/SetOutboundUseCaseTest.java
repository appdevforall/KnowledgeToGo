package org.appdevforall.k2go.privacy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class SetOutboundUseCaseTest {

    /** Records calls; {@code changed} is what set() returns; {@code restartFails} makes the request throw. */
    private static final class FakeRepo implements PrivacyRepository {
        final List<String> calls = new ArrayList<>();
        boolean changed = true;
        boolean restartFails = false;
        Runnable onSet = () -> { };
        @Override public List<OutboundSwitch> list() { return Collections.emptyList(); }
        @Override public boolean set(String s, boolean o) { calls.add("set " + s + " " + o); onSet.run(); return changed; }
        @Override public void requestRestart(String s) throws PrivacyException {
            calls.add("restart " + s);
            if (restartFails) throw new PrivacyException(PrivacyException.Reason.UNREACHABLE, "down");
        }
    }

    private static final class FakeGate implements EnvironmentGate {
        boolean busy;
        @Override public boolean isBusy() { return busy; }
    }

    @Test
    public void busyBoxWritesNothing() throws Exception {
        FakeRepo repo = new FakeRepo();
        FakeGate gate = new FakeGate();
        gate.busy = true;
        assertEquals(SetOutboundUseCase.Result.BUSY, new SetOutboundUseCase(repo, gate).execute("kolibri", true));
        assertTrue(repo.calls.isEmpty());
    }

    @Test
    public void freeBoxWritesThenAsksForThatRestart() throws Exception {
        FakeRepo repo = new FakeRepo();
        assertEquals(SetOutboundUseCase.Result.RESTARTING,
                new SetOutboundUseCase(repo, new FakeGate()).execute("forgejo", false));
        assertEquals(Arrays.asList("set forgejo false", "restart forgejo"), repo.calls);
    }

    @Test
    public void operationStartedAfterTheWriteDefersTheRestart() throws Exception {
        FakeRepo repo = new FakeRepo();
        FakeGate gate = new FakeGate();
        repo.onSet = () -> gate.busy = true;
        assertEquals(SetOutboundUseCase.Result.NEXT_START, new SetOutboundUseCase(repo, gate).execute("kolibri", false));
        assertEquals(Arrays.asList("set kolibri false"), repo.calls);
    }

    @Test
    public void unchangedValueAsksForNoRestart() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.changed = false;
        assertEquals(SetOutboundUseCase.Result.UNCHANGED,
                new SetOutboundUseCase(repo, new FakeGate()).execute("kolibri", true));
        assertEquals(Arrays.asList("set kolibri true"), repo.calls);
    }

    @Test
    public void failedRestartRequestMeansNextStart() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.restartFails = true;
        assertEquals(SetOutboundUseCase.Result.NEXT_START,
                new SetOutboundUseCase(repo, new FakeGate()).execute("kolibri", true));
    }
}
