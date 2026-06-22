package org.iiab.controller.network.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** validate -> probe -> save/revert, with fakes. Pure JVM. */
public class ConfigureDnsUseCaseTest {

    private static final class FakeRepo implements DnsConfigRepository {
        DnsConfig saved;
        boolean enabled;
        boolean disableCalled;
        @Override public DnsConfig loadEffective() { return enabled && saved != null ? saved : DnsConfig.defaults(); }
        @Override public DnsConfig loadCustom() { return saved != null ? saved : DnsConfig.defaults(); }
        @Override public boolean isCustomEnabled() { return enabled; }
        @Override public void saveCustom(DnsConfig c) { saved = c; enabled = true; }
        @Override public void disableCustom() { enabled = false; disableCalled = true; }
    }

    /** Reachable only for servers in the allow-set. */
    private static final class FakeProbe implements DnsConnectivityProbe {
        private final java.util.Set<String> reachable;
        FakeProbe(String... ok) { reachable = new java.util.HashSet<>(java.util.Arrays.asList(ok)); }
        @Override public boolean isReachable(String server, long timeoutMs) { return reachable.contains(server); }
    }

    @Test public void appliedWhenValidAndReachable() {
        FakeRepo repo = new FakeRepo();
        ConfigureDnsUseCase.Result r =
                new ConfigureDnsUseCase(repo, new FakeProbe("1.1.1.1")).execute(new DnsConfig("1.1.1.1", "8.8.8.8"));
        assertEquals(ConfigureDnsUseCase.Outcome.APPLIED, r.outcome);
        assertEquals(new DnsConfig("1.1.1.1", "8.8.8.8"), repo.saved);
        assertTrue(repo.enabled);
    }

    @Test public void invalidIsNotSavedNorReverted() {
        FakeRepo repo = new FakeRepo();
        ConfigureDnsUseCase.Result r =
                new ConfigureDnsUseCase(repo, new FakeProbe("1.1.1.1")).execute(new DnsConfig("aaa.bbb.ccc", ""));
        assertEquals(ConfigureDnsUseCase.Outcome.INVALID, r.outcome);
        assertNull(repo.saved);
        assertFalse(repo.disableCalled);
    }

    @Test public void unreachableRevertsToDefaults() {
        FakeRepo repo = new FakeRepo();
        ConfigureDnsUseCase.Result r =
                new ConfigureDnsUseCase(repo, new FakeProbe()).execute(new DnsConfig("9.9.9.9", ""));
        assertEquals(ConfigureDnsUseCase.Outcome.UNREACHABLE, r.outcome);
        assertNull(repo.saved);
        assertTrue(repo.disableCalled);
    }

    @Test public void fallsBackToSecondaryWhenPrimaryUnreachable() {
        FakeRepo repo = new FakeRepo();
        ConfigureDnsUseCase.Result r =
                new ConfigureDnsUseCase(repo, new FakeProbe("8.8.8.8")).execute(new DnsConfig("9.9.9.9", "8.8.8.8"));
        assertEquals(ConfigureDnsUseCase.Outcome.APPLIED, r.outcome);
        assertTrue(repo.enabled);
    }
}
