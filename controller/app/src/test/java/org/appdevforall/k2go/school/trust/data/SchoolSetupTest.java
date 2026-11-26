package org.appdevforall.k2go.school.trust.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.appdevforall.k2go.school.trust.domain.KeyVault;
import org.appdevforall.k2go.school.trust.domain.KeyVaultAccess;
import org.appdevforall.k2go.school.trust.domain.SchoolSetup;
import org.appdevforall.k2go.school.trust.domain.SchoolSetup.Result;
import org.appdevforall.k2go.school.trust.domain.TrustKeys;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Device zero (phase 3): create the school, save the backup, restore it on another phone. */
public class SchoolSetupTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = 1798761600L;   // 2027-01-01
    private static final long UNTIL = NOW + 180 * 86400L;
    private static final String PW = "Escuela 2027";

    private SchoolTrustFiles phone(String name) throws Exception {
        return new SchoolTrustFiles(tmp.newFolder(name));
    }

    @Test public void pbkdf2MatchesTheRfcVectors() {
        // The common PBKDF2-HMAC-SHA256 test vectors (P "password", S "salt", 32 bytes).
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
                hex(KeyVaultAccess.pbkdf2("password", "salt", 1)));
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
                hex(KeyVaultAccess.pbkdf2("password", "salt", 2)));
    }

    @Test public void theVaultOpensOnlyWithItsPasswordAndSchool() {
        byte[] secret = "the school key".getBytes(StandardCharsets.UTF_8);
        KeyVault.Sealed s = KeyVault.seal(secret, PW, "a1a1a1a1a1a1a1a1");
        assertArrayEquals(secret, KeyVault.open(s, PW, "a1a1a1a1a1a1a1a1"));
        assertNull(KeyVault.open(s, "Escuela 2028", "a1a1a1a1a1a1a1a1"));
        assertNull(KeyVault.open(s, PW, "b2b2b2b2b2b2b2b2"));
        assertNull(KeyVault.open(s, "", "a1a1a1a1a1a1a1a1"));
        // The same password from two keyboards: composed and decomposed "n with tilde".
        KeyVault.Sealed t = KeyVault.seal(secret, "Niños 2027!", "a1a1a1a1a1a1a1a1");
        assertArrayEquals(secret, KeyVault.open(t, "Niños 2027!", "a1a1a1a1a1a1a1a1"));
    }

    @Test public void theFormRules() {
        assertEquals(Result.OK, SchoolSetup.check("Escuela Benito Juarez", UNTIL, PW, PW, NOW));
        assertEquals(Result.NAME, SchoolSetup.check("   ", UNTIL, PW, PW, NOW));
        assertEquals(Result.NAME, SchoolSetup.check("A\nB", UNTIL, PW, PW, NOW));
        assertEquals(Result.DATE, SchoolSetup.check("E", NOW, PW, PW, NOW));
        assertEquals(Result.DATE, SchoolSetup.check("E", NOW + SchoolSetup.MAX_YEAR_SECONDS + 1, PW, PW, NOW));
        assertEquals(Result.PASSWORD_SHORT, SchoolSetup.check("E", UNTIL, "1234567", "1234567", NOW));
        assertEquals(Result.PASSWORD_DIFFERENT, SchoolSetup.check("E", UNTIL, PW, PW + "x", NOW));
    }

    @Test public void createBackupAndRestoreOnAnotherPhone() throws Exception {
        SchoolTrustFiles zero = phone("zero");
        assertEquals(Result.OK, new SchoolSetup(zero).create(" Escuela 1 ", UNTIL, PW, PW, NOW));
        TrustRecords.Root root = zero.root();
        assertNotNull(root);
        assertEquals("Escuela 1", root.name);
        assertNotNull(zero.vault());
        // One school per phone.
        assertEquals(Result.ALREADY_IN_SCHOOL, new SchoolSetup(zero).create("Other", UNTIL, PW, PW, NOW));

        byte[] backup = zero.backup();
        assertNotNull(backup);

        // A new phone (device zero was lost): wrong password, then the right one.
        SchoolTrustFiles spare = phone("spare");
        assertEquals(Result.WRONG_PASSWORD, new SchoolSetup(spare).restore(backup, "not the password"));
        assertNull(spare.root());
        assertEquals(Result.OK, new SchoolSetup(spare).restore(backup, PW));
        assertEquals(root.school, spare.root().school);
        assertNotNull(spare.vault());
        assertArrayEquals(backup, spare.backup());

        // The key in the backup signs for this school.
        byte[] pkcs8 = KeyVault.open(spare.vault(), PW, root.school);
        assertNotNull(TrustKeys.privateKey(pkcs8));
        assertEquals(true, TrustKeys.isPair(TrustKeys.privateKey(pkcs8), root.keySpki()));

        // A phone of another school cannot take this key.
        SchoolTrustFiles other = phone("other");
        assertEquals(Result.OK, new SchoolSetup(other).create("Escuela 2", UNTIL, PW, PW, NOW));
        assertEquals(Result.OTHER_SCHOOL, new SchoolSetup(other).restore(backup, PW));
    }

    @Test public void notABackup() throws Exception {
        SchoolTrustFiles p = phone("p");
        assertEquals(Result.NOT_A_BACKUP, new SchoolSetup(p).restore(null, PW));
        assertEquals(Result.NOT_A_BACKUP, new SchoolSetup(p).restore("{}".getBytes(StandardCharsets.UTF_8), PW));
        assertEquals(Result.NOT_A_BACKUP, new SchoolSetup(p).restore(new byte[20000], PW));
        assertNull(p.backup());
    }

    @Test public void aKeyFileWithNoPinnedRootIsNoSchool() throws Exception {
        File dir = tmp.newFolder("half");
        SchoolTrustFiles a = new SchoolTrustFiles(dir);
        assertEquals(Result.OK, new SchoolSetup(a).create("E", UNTIL, PW, PW, NOW));
        // A stop between the two writes: the key file is there, the pinned root is not.
        assertEquals(true, new File(new File(dir, "school"), "root.token").delete());
        assertNull(a.root());
        assertNull(a.vault());
        assertNull(a.backup());
        assertEquals(Result.OK, new SchoolSetup(a).create("E", UNTIL, PW, PW, NOW));
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
