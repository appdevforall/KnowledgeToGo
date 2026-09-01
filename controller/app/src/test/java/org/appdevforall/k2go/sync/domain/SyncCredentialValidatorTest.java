package org.appdevforall.k2go.sync.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.SyncHandshakeHelper;
import org.junit.Test;

/**
 * Unit tests for {@link SyncCredentialValidator} — the domain rule that closes
 * tech-debt item S1 (rsyncd.conf / rsync:// URL injection from scanned QR
 * credentials). Pure JVM, no Android dependencies.
 */
public class SyncCredentialValidatorTest {

    // --- happy path ---

    @Test
    public void acceptsTypicalLanCredentials() {
        assertTrue(SyncCredentialValidator
                .validateCredentials("192.168.1.50", 8730, "iiab_peer", "s3cretPass").valid);
    }

    @Test
    public void acceptsGeneratedPassword() {
        // The validator must never reject a password the app itself produces.
        for (int i = 0; i < 200; i++) {
            String pwd = SyncHandshakeHelper.generateSecurePassword();
            assertTrue("rejected a generated password: " + pwd,
                    SyncCredentialValidator.isValidPassword(pwd));
        }
    }

    // --- username ---

    @Test
    public void rejectsUsernameWithNewlineInjection() {
        // The classic S1 attack: smuggle a new rsyncd.conf directive.
        assertFalse(SyncCredentialValidator.isValidUsername("iiab\n[evil]\npath = /"));
    }

    @Test
    public void rejectsUsernameWithUrlMetacharacters() {
        assertFalse(SyncCredentialValidator.isValidUsername("a@b"));
        assertFalse(SyncCredentialValidator.isValidUsername("a:b"));
        assertFalse(SyncCredentialValidator.isValidUsername("a/b"));
        assertFalse(SyncCredentialValidator.isValidUsername("a b"));
    }

    @Test
    public void rejectsEmptyOrOverlongOrLeadingDashUsername() {
        assertFalse(SyncCredentialValidator.isValidUsername(""));
        assertFalse(SyncCredentialValidator.isValidUsername(null));
        assertFalse(SyncCredentialValidator.isValidUsername("-leadingdash"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) sb.append('a');
        assertFalse(SyncCredentialValidator.isValidUsername(sb.toString()));
    }

    @Test
    public void acceptsPlainUsernames() {
        assertTrue(SyncCredentialValidator.isValidUsername("iiab_peer"));
        assertTrue(SyncCredentialValidator.isValidUsername("u"));
        assertTrue(SyncCredentialValidator.isValidUsername("host-1_2"));
    }

    // --- password ---

    @Test
    public void rejectsPasswordWithControlChars() {
        assertFalse(SyncCredentialValidator.isValidPassword("good\npart"));
        assertFalse(SyncCredentialValidator.isValidPassword("good\rpart"));
        assertFalse(SyncCredentialValidator.isValidPassword("has space"));
        assertFalse(SyncCredentialValidator.isValidPassword("tab\tinside"));
        assertFalse(SyncCredentialValidator.isValidPassword(""));
        assertFalse(SyncCredentialValidator.isValidPassword(null));
    }

    // --- host ---

    @Test
    public void rejectsHostWithInjectionOrMetacharacters() {
        assertFalse(SyncCredentialValidator.isValidHost("10.0.0.1/extra"));
        assertFalse(SyncCredentialValidator.isValidHost("10.0.0.1@evil"));
        assertFalse(SyncCredentialValidator.isValidHost("10.0.0.1 8730"));
        assertFalse(SyncCredentialValidator.isValidHost("host\nname"));
        assertFalse(SyncCredentialValidator.isValidHost(".bad"));
        assertFalse(SyncCredentialValidator.isValidHost("a..b"));
        assertFalse(SyncCredentialValidator.isValidHost(""));
        assertFalse(SyncCredentialValidator.isValidHost(null));
    }

    @Test
    public void acceptsIpv4AndHostnames() {
        assertTrue(SyncCredentialValidator.isValidHost("192.168.1.50"));
        assertTrue(SyncCredentialValidator.isValidHost("iiab-host.local"));
    }

    // --- port ---

    @Test
    public void rejectsOutOfRangePorts() {
        assertFalse(SyncCredentialValidator.isValidPort(0));
        assertFalse(SyncCredentialValidator.isValidPort(-1));
        assertFalse(SyncCredentialValidator.isValidPort(70000));
        assertTrue(SyncCredentialValidator.isValidPort(8730));
        assertTrue(SyncCredentialValidator.isValidPort(1));
        assertTrue(SyncCredentialValidator.isValidPort(65535));
    }

    // --- rsyncd.conf value safety (server side, e.g. shared path) ---

    @Test
    public void rejectsConfigValueWithNewline() {
        assertFalse(SyncCredentialValidator.isSafeConfigValue("/data\nread only = no"));
        assertFalse(SyncCredentialValidator.isSafeConfigValue("/data\rx"));
        assertFalse(SyncCredentialValidator.isSafeConfigValue(null));
    }

    @Test
    public void acceptsNormalPath() {
        assertTrue(SyncCredentialValidator.isSafeConfigValue(
                "/data/user/0/org.appdevforall.k2go/files/rootfs"));
    }

    // --- aggregate ---

    @Test
    public void validateCredentialsFailsOnAnyBadField() {
        assertFalse(SyncCredentialValidator
                .validateCredentials("10.0.0.1", 8730, "ok", "bad\npass").valid);
        assertFalse(SyncCredentialValidator
                .validateCredentials("10.0.0.1", 0, "ok", "ok").valid);
        assertFalse(SyncCredentialValidator
                .validateCredentials("bad host", 8730, "ok", "ok").valid);
    }
}
