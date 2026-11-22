package org.appdevforall.k2go.privacy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class KeepBoxOfflineUseCaseTest {

    private static class FakeRepo implements PrivacyRepository {
        final List<OutboundSwitch> reported;
        final List<String> writes = new ArrayList<>();
        FakeRepo(OutboundSwitch... s) { reported = Arrays.asList(s); }
        @Override public List<OutboundSwitch> list() { return reported; }
        @Override public boolean set(String s, boolean o) throws PrivacyException {
            writes.add(s + "=" + o);
            return true;
        }
        @Override public void requestRestart(String s) { throw new AssertionError("no restart here"); }
    }

    @Test
    public void switchesOffEveryInstalledServiceThatIsOn() throws Exception {
        FakeRepo repo = new FakeRepo(
                new OutboundSwitch(OutboundSwitch.KOLIBRI, true, true),
                new OutboundSwitch(OutboundSwitch.FORGEJO, true, true));
        assertEquals(Arrays.asList("kolibri", "forgejo"), new KeepBoxOfflineUseCase(repo).execute());
        assertEquals(Arrays.asList("kolibri=false", "forgejo=false"), repo.writes);
    }

    @Test
    public void writesNothingWhenEverythingIsAlreadyOff() throws Exception {
        FakeRepo repo = new FakeRepo(
                new OutboundSwitch(OutboundSwitch.KOLIBRI, true, false),
                new OutboundSwitch(OutboundSwitch.FORGEJO, true, false, true));
        assertTrue(new KeepBoxOfflineUseCase(repo).execute().isEmpty());
        assertTrue(repo.writes.isEmpty());
    }

    @Test
    public void aFailingServiceDoesNotStopTheOthers() {
        FakeRepo repo = new FakeRepo(
                new OutboundSwitch(OutboundSwitch.KOLIBRI, true, true),
                new OutboundSwitch(OutboundSwitch.FORGEJO, true, true)) {
            @Override public boolean set(String s, boolean o) throws PrivacyException {
                if (s.equals(OutboundSwitch.KOLIBRI)) {
                    throw new PrivacyException(PrivacyException.Reason.FAILED, "kolibri");
                }
                return super.set(s, o);
            }
        };
        try {
            new KeepBoxOfflineUseCase(repo).execute();
            fail("the failure must be reported");
        } catch (PrivacyException e) {
            assertEquals("kolibri", e.getMessage());
        }
        assertEquals(Arrays.asList("forgejo=false"), repo.writes);
    }

    @Test
    public void skipsAServiceThatIsNotInstalled() throws Exception {
        FakeRepo repo = new FakeRepo(new OutboundSwitch(OutboundSwitch.FORGEJO, false, true));
        assertTrue(new KeepBoxOfflineUseCase(repo).execute().isEmpty());
        assertTrue(repo.writes.isEmpty());
    }
}
