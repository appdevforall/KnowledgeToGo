package org.appdevforall.k2go;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure (framework-free) parts of {@link SyncHandshakeHelper}:
 * password generation and the QR payload create/parse round-trip.
 *
 * <p>Relies on {@code testOptions.unitTests.returnDefaultValues = true} (so
 * {@code android.util.Log} calls no-op) and the real {@code org.json} test dependency.
 */
public class SyncHandshakeHelperTest {

    // --- generateSecurePassword ---

    @Test
    public void generateSecurePassword_hasExpectedLength() {
        assertEquals(12, SyncHandshakeHelper.generateSecurePassword().length());
    }

    @Test
    public void generateSecurePassword_usesOnlyAlphanumericChars() {
        String pwd = SyncHandshakeHelper.generateSecurePassword();
        assertTrue("password should be alphanumeric: " + pwd, pwd.matches("[A-Za-z0-9]+"));
    }

    @Test
    public void generateSecurePassword_isNotConstant() {
        // Extremely unlikely to collide; guards against a degenerate generator.
        assertTrue(!SyncHandshakeHelper.generateSecurePassword()
                .equals(SyncHandshakeHelper.generateSecurePassword()));
    }

    // --- createPayload / parsePayload round-trip ---

    @Test
    public void payload_roundTripsAllFields() {
        String payload = SyncHandshakeHelper.createPayload(
                "192.168.1.50", 8730, "iiab_peer", "s3cretPass", true, 64);

        SyncHandshakeHelper.SyncCredentials creds = SyncHandshakeHelper.parsePayload(payload);

        assertNotNull(creds);
        assertEquals("192.168.1.50", creds.ip);
        assertEquals(8730, creds.port);
        assertEquals("iiab_peer", creds.user);
        assertEquals("s3cretPass", creds.pass);
        assertTrue(creds.hasRootfs);
        assertEquals(64, creds.archBits);
    }

    @Test
    public void parsePayload_returnsNullForNonIiabJson() {
        assertNull(SyncHandshakeHelper.parsePayload("{\"app\":\"some_other_app\"}"));
    }

    @Test
    public void parsePayload_returnsNullForMalformedJson() {
        assertNull(SyncHandshakeHelper.parsePayload("this is not json"));
    }

    @Test
    public void parsePayload_returnsNullForNull() {
        assertNull(SyncHandshakeHelper.parsePayload(null));
    }

    @Test
    public void parsePayload_defaultsHasRootfsTrueForLegacyPayload() {
        // Legacy payloads without "has_rootfs" should default to true.
        String legacy = "{\"app\":\"iiab_sync\",\"ip\":\"10.0.0.1\",\"port\":8730,"
                + "\"user\":\"u\",\"pass\":\"p\"}";
        SyncHandshakeHelper.SyncCredentials creds = SyncHandshakeHelper.parsePayload(legacy);
        assertNotNull(creds);
        assertTrue(creds.hasRootfs);
        assertEquals(0, creds.archBits);
    }
    // --- S1: malicious payloads are rejected at the parse boundary ---

    @Test
    public void parsePayload_rejectsRsyncdConfInjectionInUsername() {
        // A username carrying a newline + a new rsyncd.conf section.
        String malicious = "{\"app\":\"iiab_sync\",\"ip\":\"10.0.0.1\",\"port\":8730,"
                + "\"user\":\"iiab\\n[evil]\\npath = /\",\"pass\":\"p\"}";
        assertNull(SyncHandshakeHelper.parsePayload(malicious));
    }

    @Test
    public void parsePayload_rejectsUrlBreakoutInHost() {
        String malicious = "{\"app\":\"iiab_sync\",\"ip\":\"10.0.0.1/evil\",\"port\":8730,"
                + "\"user\":\"iiab_peer\",\"pass\":\"p\"}";
        assertNull(SyncHandshakeHelper.parsePayload(malicious));
    }

    @Test
    public void parsePayload_rejectsOutOfRangePort() {
        String malicious = "{\"app\":\"iiab_sync\",\"ip\":\"10.0.0.1\",\"port\":70000,"
                + "\"user\":\"iiab_peer\",\"pass\":\"p\"}";
        assertNull(SyncHandshakeHelper.parsePayload(malicious));
    }
}
