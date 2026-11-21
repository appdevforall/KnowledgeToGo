package org.appdevforall.k2go.privacy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class KeepBoxOfflineUseCaseTest {

    private static final class FakeRepo implements PrivacyRepository {
        final List<OutboundSwitch> reported;
        final List<String> writes = new ArrayList<>();
        FakeRepo(OutboundSwitch... s) { reported = Arrays.asList(s); }
        @Override public List<OutboundSwitch> list() { return reported; }
        @Override public boolean set(String s, boolean o) {
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
    public void skipsAServiceThatIsNotInstalled() throws Exception {
        FakeRepo repo = new FakeRepo(new OutboundSwitch(OutboundSwitch.FORGEJO, false, true));
        assertTrue(new KeepBoxOfflineUseCase(repo).execute().isEmpty());
        assertTrue(repo.writes.isEmpty());
    }
}
