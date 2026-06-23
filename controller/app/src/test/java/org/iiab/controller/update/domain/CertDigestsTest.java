package org.iiab.controller.update.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class CertDigestsTest {

    private static Set<String> of(String... v) {
        return new HashSet<>(java.util.Arrays.asList(v));
    }

    @Test
    public void acceptsIdenticalNonEmptySets() {
        assertTrue(CertDigests.sameSigner(of("AA:BB"), of("AA:BB")));
    }

    @Test
    public void rejectsDifferentSigner() {
        assertFalse(CertDigests.sameSigner(of("AA:BB"), of("CC:DD")));
    }

    @Test
    public void rejectsEmptyCandidate() {
        // e.g. the download was a text/HTML page, not a signed APK
        assertFalse(CertDigests.sameSigner(of("AA:BB"), Collections.emptySet()));
    }

    @Test
    public void rejectsNullsAndEmptyApp() {
        assertFalse(CertDigests.sameSigner(null, of("AA")));
        assertFalse(CertDigests.sameSigner(of("AA"), null));
        assertFalse(CertDigests.sameSigner(Collections.emptySet(), of("AA")));
    }
}
