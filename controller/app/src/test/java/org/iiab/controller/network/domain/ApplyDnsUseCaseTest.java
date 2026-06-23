package org.iiab.controller.network.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

/** Apply writes whatever loadEffective() returns, and propagates the writer result. */
public class ApplyDnsUseCaseTest {

    private static final class FakeRepo implements DnsConfigRepository {
        private final DnsConfig effective;
        FakeRepo(DnsConfig effective) { this.effective = effective; }
        @Override public DnsConfig loadEffective() { return effective; }
        @Override public DnsConfig loadCustom() { return effective; }
        @Override public boolean isCustomEnabled() { return false; }
        @Override public void saveCustom(DnsConfig c) { }
        @Override public void disableCustom() { }
    }

    private static final class FakeWriter implements ResolvConfWriter {
        DnsConfig got;
        File dir;
        private final boolean ret;
        FakeWriter(boolean ret) { this.ret = ret; }
        @Override public boolean write(DnsConfig config, File rootfsDir) { got = config; dir = rootfsDir; return ret; }
    }

    @Test public void writesEffectiveConfig() {
        DnsConfig eff = DnsConfig.defaults();
        FakeWriter w = new FakeWriter(true);
        boolean ok = new ApplyDnsUseCase(new FakeRepo(eff), w).execute(new File("/tmp/rootfs"));
        assertTrue(ok);
        assertEquals(eff, w.got);
        assertEquals(new File("/tmp/rootfs"), w.dir);
    }

    @Test public void propagatesWriterFailure() {
        boolean ok = new ApplyDnsUseCase(new FakeRepo(DnsConfig.defaults()), new FakeWriter(false)).execute(new File("/x"));
        assertFalse(ok);
    }
}
