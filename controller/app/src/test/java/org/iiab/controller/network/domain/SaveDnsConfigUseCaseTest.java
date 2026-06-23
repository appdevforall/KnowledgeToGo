package org.iiab.controller.network.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Save validates first: valid configs are persisted+enabled; invalid ones are not. */
public class SaveDnsConfigUseCaseTest {

    private static final class FakeRepo implements DnsConfigRepository {
        DnsConfig saved;
        boolean enabled;
        @Override public DnsConfig loadEffective() { return enabled && saved != null ? saved : DnsConfig.defaults(); }
        @Override public DnsConfig loadCustom() { return saved != null ? saved : DnsConfig.defaults(); }
        @Override public boolean isCustomEnabled() { return enabled; }
        @Override public void saveCustom(DnsConfig c) { saved = c; enabled = true; }
        @Override public void disableCustom() { enabled = false; }
    }

    @Test public void validConfigIsSavedAndEnabled() {
        FakeRepo repo = new FakeRepo();
        DnsValidator.Result r = new SaveDnsConfigUseCase(repo).execute(new DnsConfig("1.1.1.1", "8.8.8.8"));
        assertTrue(r.valid);
        assertEquals(new DnsConfig("1.1.1.1", "8.8.8.8"), repo.saved);
        assertTrue(repo.enabled);
    }

    @Test public void invalidConfigIsNotSaved() {
        FakeRepo repo = new FakeRepo();
        DnsValidator.Result r = new SaveDnsConfigUseCase(repo).execute(new DnsConfig("aaa.bbb.ccc", "xxx.yyy.zzz"));
        assertFalse(r.valid);
        assertNull(repo.saved);
        assertFalse(repo.enabled);
    }
}
