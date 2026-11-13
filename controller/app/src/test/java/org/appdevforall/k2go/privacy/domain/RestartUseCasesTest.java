package org.appdevforall.k2go.privacy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class RestartUseCasesTest {

    private static final class FakeRepo implements PrivacyRepository {
        List<OutboundSwitch> reported = Collections.emptyList();
        final List<String> restarts = new ArrayList<>();
        boolean restartFails = false;
        @Override public List<OutboundSwitch> list() { return reported; }
        @Override public boolean set(String s, boolean o) { throw new AssertionError("no write here"); }
        @Override public void requestRestart(String s) throws PrivacyException {
            if (restartFails) throw new PrivacyException(PrivacyException.Reason.UNREACHABLE, "down");
            restarts.add(s);
        }
    }

    private static EnvironmentGate gate(boolean busy) { return () -> busy; }

    // --- Restart now ---

    @Test
    public void restartNowAsksTheOwnerWhenNoDestructiveOperationRuns() {
        FakeRepo repo = new FakeRepo();
        assertEquals(RestartServiceUseCase.Result.RESTARTING, new RestartServiceUseCase(repo, gate(false)).execute("kolibri"));
        assertEquals(Arrays.asList("kolibri"), repo.restarts);
    }

    @Test
    public void restartNowIsRefusedDuringADestructiveOperation() {
        FakeRepo repo = new FakeRepo();
        assertEquals(RestartServiceUseCase.Result.BUSY, new RestartServiceUseCase(repo, gate(true)).execute("kolibri"));
        assertTrue(repo.restarts.isEmpty());
    }

    @Test
    public void restartNowReportsAFailedRequest() {
        FakeRepo repo = new FakeRepo();
        repo.restartFails = true;
        assertEquals(RestartServiceUseCase.Result.FAILED, new RestartServiceUseCase(repo, gate(false)).execute("forgejo"));
    }

    // --- At app open ---

    @Test
    public void appOpenRestartsOnlyInstalledPendingServices() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.reported = Arrays.asList(
                new OutboundSwitch(OutboundSwitch.KOLIBRI, true, false, true),
                new OutboundSwitch(OutboundSwitch.FORGEJO, true, true, false));
        assertEquals(Arrays.asList("kolibri"), new ApplyPendingRestartsUseCase(repo, gate(false)).execute());
        assertEquals(Arrays.asList("kolibri"), repo.restarts);
    }

    @Test
    public void appOpenDoesNothingDuringADestructiveOperation() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.reported = Arrays.asList(new OutboundSwitch(OutboundSwitch.KOLIBRI, true, false, true));
        assertTrue(new ApplyPendingRestartsUseCase(repo, gate(true)).execute().isEmpty());
        assertTrue(repo.restarts.isEmpty());
    }

    @Test
    public void appOpenSkipsAServiceThatIsNotInstalled() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.reported = Arrays.asList(new OutboundSwitch(OutboundSwitch.FORGEJO, false, false, true));
        assertTrue(new ApplyPendingRestartsUseCase(repo, gate(false)).execute().isEmpty());
    }
}
