package org.appdevforall.k2go.school.trust.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.school.domain.SchoolRole;
import org.appdevforall.k2go.school.trust.domain.Base64Url;
import org.appdevforall.k2go.school.trust.domain.TrustCheck;
import org.appdevforall.k2go.school.trust.domain.TrustCheck.Verdict;
import org.appdevforall.k2go.school.trust.domain.TrustKeys;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Collections;

/**
 * The school trust against the shared vectors (static/dashboard/school/trust-vectors.json). The box
 * runs the same vectors (school/trust.test.ts), so the app and the box keep the same rules.
 */
public class TrustCheckTest {

    private static final JSONObject V = vectors();
    private static final long NOW = V.optLong("now");
    private static final TrustRecords.Root ROOT = TrustCodec.readRoot(V.optString("root"));
    private static final TrustRecords.Removed REMOVED = TrustRecords.Removed.merge(
            TrustCodec.readRemoved(ROOT, V.optJSONObject("removed").optString("first")),
            TrustCodec.readRemoved(ROOT, V.optJSONObject("removed").optString("second")));

    private static JSONObject vectors() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (; dir != null; dir = dir.getParentFile()) {
            File f = new File(dir, "static/dashboard/school/trust-vectors.json");
            if (f.isFile()) {
                try {
                    return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("trust-vectors.json not found above " + System.getProperty("user.dir"));
    }

    private static String teacher(String name) { return V.optJSONObject("teacher").optString(name); }

    private static Verdict verdict(String token) { return TrustCodec.checkTeacher(ROOT, token, REMOVED, NOW).verdict; }

    private static PrivateKey privateKey(String pkcs8B64) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64Url.decodeStandard(pkcs8B64)));
    }

    @Test public void theRootRecord() {
        assertNotNull(ROOT);
        assertEquals(V.optString("school"), ROOT.school);
        assertEquals("Test school", ROOT.name);
        assertNotNull(TrustCodec.readRoot(V.optString("rootOtherSchool")));
        assertNull(TrustCodec.readRoot(V.optString("rootWrongSchoolId")));
        assertNull(TrustCodec.readRoot(V.optString("rootSignedByOther")));
        assertNull(TrustCodec.readRoot(teacher("verified")));
        StringBuilder longToken = new StringBuilder();
        for (int i = 0; i < 5000; i++) longToken.append('x');
        assertNull(TrustCodec.readRoot(longToken.toString()));
    }

    @Test public void teacherCertificatesEachVerdict() {
        assertEquals(Verdict.VERIFIED, verdict(teacher("verified")));
        assertEquals(Verdict.EXPIRED, verdict(teacher("expired")));
        assertEquals(Verdict.NOT_YET, verdict(teacher("notYet")));
        assertEquals(Verdict.OTHER_SCHOOL, verdict(teacher("otherSchool")));
        assertEquals(Verdict.BAD_SIGNATURE, verdict(teacher("signedByOther")));
        assertEquals(Verdict.BAD_SIGNATURE, verdict(teacher("tampered")));
        assertEquals(Verdict.REMOVED, verdict(teacher("removed")));
        assertEquals(Verdict.MALFORMED, verdict(teacher("wrongKind")));
        assertEquals(Verdict.MALFORMED, verdict(teacher("badVersion")));
        assertEquals(Verdict.MALFORMED, verdict(teacher("notAToken")));
        assertEquals(Verdict.MALFORMED, verdict(null));
        assertEquals("Room 3B", TrustCodec.checkTeacher(ROOT, teacher("verified"), REMOVED, NOW).cert.label);
    }

    @Test public void removedListsOnlyFromTheRootAndMerged() {
        assertNull(TrustCodec.readRemoved(ROOT, V.optJSONObject("removed").optString("signedByOther")));
        assertNull(TrustCodec.readRemoved(ROOT, teacher("verified")));
        assertEquals(Arrays.asList("b2b2b2b2b2b2b2b2", "c3c3c3c3c3c3c3c3"), REMOVED.serials);
        assertEquals(NOW - 50, REMOVED.issued);
        TrustRecords.Removed again = TrustRecords.Removed.merge(REMOVED,
                TrustCodec.readRemoved(ROOT, V.optJSONObject("removed").optString("first")));
        assertEquals(REMOVED.serials, again.serials);
        assertNull(TrustRecords.Removed.merge(null, null));
    }

    @Test public void theDerivedRole() {
        assertEquals(SchoolRole.NONE, TrustCheck.role(null, null));
        assertEquals(SchoolRole.STUDENT, TrustCheck.role(ROOT, null));
        assertEquals(SchoolRole.TEACHER, TrustCheck.role(ROOT, verdict(teacher("verified"))));
        assertEquals(SchoolRole.STUDENT, TrustCheck.role(ROOT, verdict(teacher("removed"))));
        assertEquals(SchoolRole.STUDENT, TrustCheck.role(ROOT, verdict(teacher("expired"))));
    }

    @Test public void theClassProofFromTheBox() {
        TrustRecords.Teacher cert = TrustCodec.checkTeacher(ROOT, teacher("verified"), REMOVED, NOW).cert;
        JSONObject p = V.optJSONObject("proof");
        String nonce = p.optString("nonce");
        String code = p.optString("code");
        String host = p.optString("host");
        String sig = p.optString("sig");
        assertTrue(TrustCheck.proof(cert, nonce, code, host, sig));
        assertFalse(TrustCheck.proof(cert, nonce, "XXXX", host, sig));
        assertFalse(TrustCheck.proof(cert, "other-nonce", code, host, sig));
        assertFalse(TrustCheck.proof(cert, nonce, code, "192.168.1.99:8085", sig));   // relayed by another box
        assertFalse(TrustCheck.proof(cert, nonce, code, host, "AAAA"));
        assertFalse(TrustCheck.proof(cert, nonce + "\n" + code, "X", host, sig));
        assertNull(TrustKeys.proofBytes("n 2", "X", "h:1"));
        assertNull(TrustKeys.proofBytes("", "X", "h:1"));
    }

    @Test public void aCertificateEndsWithTheYearOfItsRoot() throws Exception {
        PrivateKey root = privateKey(V.optJSONObject("keys").optString("rootPkcs8"));
        String spki = V.optJSONObject("keys").optString("teacherSpki");
        String cert = TrustCodec.signTeacher(root, ROOT.school, spki, "R", "d5d5d5d5d5d5d5d5", 0, ROOT.until + 1000);
        assertEquals(Verdict.VERIFIED, TrustCodec.checkTeacher(ROOT, cert, null, ROOT.until - 1).verdict);
        assertEquals(Verdict.EXPIRED, TrustCodec.checkTeacher(ROOT, cert, null, ROOT.until).verdict);
    }

    @Test public void listsOfTwoSchoolsDoNotMerge() {
        TrustRecords.Removed other = TrustRecords.Removed.of(TrustRecords.VERSION, "ffffffffffffffff", NOW + 9,
                Collections.singletonList("a1a1a1a1a1a1a1a1"));
        assertEquals(REMOVED.serials, TrustRecords.Removed.merge(REMOVED, other).serials);
    }

    @Test public void aTeacherCheckWithNoRootIsMalformed() {
        assertEquals(Verdict.MALFORMED, TrustCodec.checkTeacher(null, teacher("verified"), null, NOW).verdict);
    }

    @Test public void aKeyOfAnotherCurveIsRefused() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp384r1"));
        assertNull(TrustKeys.publicKey(g.generateKeyPair().getPublic().getEncoded()));
        assertNull(TrustKeys.publicKey(new byte[] {1, 2, 3}));
    }

    /** The app signs (device zero); the app and the box read it. The box test reads "javaSigned". */
    @Test public void aCertificateThatTheAppSigns() throws Exception {
        PrivateKey root = privateKey(V.optJSONObject("keys").optString("rootPkcs8"));
        String spki = V.optJSONObject("keys").optString("teacherSpki");
        String cert = TrustCodec.signTeacher(root, ROOT.school, spki, "Room 7J", "f6f6f6f6f6f6f6f6", NOW - 10, ROOT.until);
        String removed = TrustCodec.signRemoved(root, ROOT.school, NOW, Collections.singletonList("e5e5e5e5e5e5e5e5"));
        assertEquals(Verdict.VERIFIED, TrustCodec.checkTeacher(ROOT, cert, null, NOW).verdict);
        assertEquals(Collections.singletonList("e5e5e5e5e5e5e5e5"), TrustCodec.readRemoved(ROOT, removed).serials);
        // The entry for the vectors file, when it is made again (trust-vectors.gen.ts).
        System.out.println("javaSigned: " + new JSONObject().put("teacher", cert).put("removed", removed));

        JSONObject j = V.optJSONObject("javaSigned");
        assertNotNull("trust-vectors.json has no javaSigned entry: copy the line above into it", j);
        assertEquals(Verdict.VERIFIED, TrustCodec.checkTeacher(ROOT, j.optString("teacher"), null, NOW).verdict);
    }

    @Test public void theSignersRefuseWhatTheReadersRefuse() throws Exception {
        PrivateKey root = privateKey(V.optJSONObject("keys").optString("rootPkcs8"));
        String spki = V.optJSONObject("keys").optString("teacherSpki");
        String longLabel = "Room 3B with a label that is longer than forty characters";
        assertThrows(() -> TrustCodec.signTeacher(root, ROOT.school, spki, longLabel, "f6f6f6f6f6f6f6f6", 0, 1));
        assertThrows(() -> TrustCodec.signTeacher(root, ROOT.school, spki, "R", "not-hex", 0, 1));
        java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < TrustRecords.MAX_SERIALS; i++) many.add(String.format("%016x", i));
        String full = TrustCodec.signRemoved(root, ROOT.school, NOW, many);   // the largest list fits
        assertEquals(TrustRecords.MAX_SERIALS, TrustCodec.readRemoved(ROOT, full).serials.size());
        many.add("ffffffffffffffff");
        assertThrows(() -> TrustCodec.signRemoved(root, ROOT.school, NOW, many));
    }

    private static void assertThrows(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("no IllegalArgumentException");
    }

    @Test public void base64RoundTrip() {
        for (int n = 0; n < 40; n++) {
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) b[i] = (byte) (i * 37 + n);
            assertTrue(Arrays.equals(b, Base64Url.decode(Base64Url.encode(b))));
        }
        assertNull(Base64Url.decode("a+b"));
        assertNull(Base64Url.decode("abcde"));
        assertNull(Base64Url.decodeStandard("ab==="));
        assertTrue(Arrays.equals(new byte[] {(byte) 0xfb, (byte) 0xff}, Base64Url.decodeStandard("+/8=")));
    }
}
