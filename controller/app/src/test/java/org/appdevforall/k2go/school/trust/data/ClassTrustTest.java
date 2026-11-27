package org.appdevforall.k2go.school.trust.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.school.domain.SchoolRole;
import org.appdevforall.k2go.school.trust.domain.Base64Url;
import org.appdevforall.k2go.school.trust.domain.PhoneRole;
import org.appdevforall.k2go.school.trust.domain.SchoolRing;
import org.appdevforall.k2go.school.trust.domain.TrustKeys;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;
import org.appdevforall.k2go.school.trust.domain.VerifyClass;
import org.appdevforall.k2go.school.trust.domain.VouchClass;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

/**
 * The verified class (design 6.4): the role from the trust files, the vouch of a teacher phone, and
 * the check of a student phone. The fake box below does what static/dashboard/school/vouch.ts does.
 */
public class ClassTrustTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final JSONObject V = TrustCheckTest.vectorsForTests();
    private static final long NOW = V.optLong("now");
    private static final String CERT = V.optJSONObject("teacher").optString("verified");
    private static final String BOX = "192.168.1.20";
    private static final int PORT = 8085;

    private SchoolTrustFiles phone(String name, boolean teacher) throws Exception {
        SchoolTrustFiles f = new SchoolTrustFiles(tmp.newFolder(name));
        assertTrue(f.pinRoot(V.optString("root")));
        if (teacher) assertTrue(f.saveTeacherCert(CERT));
        return f;
    }

    /** A teacher box: its class key, the vouch of its teacher phone, and its own address for the proof. */
    private static final class FakeBox implements VerifyClass.Hello, VouchClass.Box {
        final KeyPair classKey = TrustKeys.generate();
        String code = "86GZ";
        String cert;
        String vouch;
        int gives;
        boolean down;   // the box does not answer

        String classKeyB64() { return Base64Url.encodeStandard(classKey.getPublic().getEncoded()); }

        @Override public Key classKey() { return new Key(code, classKeyB64(), vouch != null); }

        @Override public boolean give(String code, String cert, String vouch) {
            gives++;
            this.cert = cert;
            this.vouch = vouch;
            return true;
        }

        @Override public Answer ask(String address, int port, String nonce) {
            if (down) return null;
            if (vouch == null) return new Answer(null, null, null, null);   // 404: no vouch
            byte[] data = TrustKeys.proofBytes(nonce, code, BOX + ":" + PORT);   // nginx gives the box its own address
            return new Answer(code, cert, vouch, Base64Url.encode(TrustKeys.sign(classKey.getPrivate(), data)));
        }
    }

    @Test public void theRoleComesFromTheTrustFiles() throws Exception {
        SchoolRing.TeacherKey key = TrustCheckTest.vectorTeacherKey();
        SchoolTrustFiles none = new SchoolTrustFiles(tmp.newFolder("none"));
        assertEquals(SchoolRole.NONE, PhoneRole.of(none, key, NOW));
        assertEquals(SchoolRole.STUDENT, PhoneRole.of(phone("student", false), key, NOW));
        SchoolTrustFiles teacher = phone("teacher", true);
        assertEquals(SchoolRole.TEACHER, PhoneRole.of(teacher, key, NOW));
        // A certificate copied from another phone: not the teacher key of this phone.
        SchoolRing.TeacherKey other = new SchoolRing.TeacherKey() {
            final String pub = Base64Url.encodeStandard(TrustKeys.generate().getPublic().getEncoded());
            @Override public String publicKey() { return pub; }
            @Override public byte[] sign(byte[] data) { return null; }
        };
        assertEquals(SchoolRole.STUDENT, PhoneRole.of(teacher, other, NOW));
        assertEquals(SchoolRole.STUDENT, PhoneRole.of(teacher, key, 9_000_000_000L));   // the certificate ended
        assertFalse(PhoneRole.isDeviceZero(teacher));
        // A root file that is not a valid root: a student (a file of the school takes rights away).
        File dir = tmp.newFolder("broken");
        File root = new File(SchoolTrustFiles.boxDir(dir), "root.token");
        assertTrue(root.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(root)) {
            out.write("not a root".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals(SchoolRole.STUDENT, PhoneRole.of(new SchoolTrustFiles(dir), key, NOW));
    }

    @Test public void aTeacherPhoneVouchesForItsClassAndAStudentPhoneVerifiesIt() throws Exception {
        FakeBox box = new FakeBox();
        VouchClass vouch = new VouchClass(phone("teacher", true), box, TrustCheckTest.vectorTeacherKey());
        assertEquals(VouchClass.Result.VOUCHED, vouch.run(NOW));
        assertEquals(1, box.gives);
        assertEquals(VouchClass.Result.VOUCHED, vouch.run(NOW));   // the box has it: no second vouch
        assertEquals(1, box.gives);

        SchoolTrustFiles student = phone("student", false);
        VerifyClass verify = new VerifyClass(student, box);
        VerifyClass.Result ok = verify.check(BOX, PORT, "86GZ", NOW);
        assertEquals(VerifyClass.Kind.VERIFIED, ok.kind);
        assertEquals("Room 3B", ok.label);
        // A false box at another address that relays the nonce to the real box.
        assertEquals(VerifyClass.Kind.NOT_VERIFIED, verify.check("192.168.1.99", PORT, "86GZ", NOW).kind);
        // The list knows another code for the class.
        assertEquals(VerifyClass.Kind.NOT_VERIFIED, verify.check(BOX, PORT, "XXXX", NOW).kind);
        // The vouch ended (a class does not run longer than VOUCH_SECONDS).
        assertEquals(VerifyClass.Kind.NOT_VERIFIED, verify.check(BOX, PORT, "86GZ", NOW + VouchClass.VOUCH_SECONDS).kind);
        // The box does not answer (a Wi-Fi drop): not a verdict on the class (the list keeps a verified row).
        box.down = true;
        assertEquals(VerifyClass.Kind.NO_ANSWER, verify.check(BOX, PORT, "86GZ", NOW).kind);
        assertFalse(VerifyClass.Result.NO_ANSWER.canJoin());
        box.down = false;
        // A phone with no school sees every class as before.
        assertEquals(VerifyClass.Kind.NO_SCHOOL,
                new VerifyClass(new SchoolTrustFiles(tmp.newFolder("none")), box).check(BOX, PORT, "86GZ", NOW).kind);
    }

    @Test public void aClassWithNoVouchOrAVouchOfAnotherClassKeyIsNotVerified() throws Exception {
        FakeBox box = new FakeBox();
        VerifyClass verify = new VerifyClass(phone("student", false), box);
        assertEquals(VerifyClass.Kind.NOT_VERIFIED, verify.check(BOX, PORT, "86GZ", NOW).kind);   // no vouch: no answer
        TrustRecords.Teacher cert = TrustCodec.checkTeacher(TrustCodec.readRoot(V.optString("root")), CERT, null, NOW).cert;
        // A box that shows the vouch of a real class with its own class key.
        box.give("86GZ", CERT, TrustCodec.signVouch(TrustCheckTest.vectorTeacherKey(), cert, "86GZ",
                V.optJSONObject("vouch").optString("classKey"), NOW + 3600));
        assertEquals(VerifyClass.Kind.NOT_VERIFIED, verify.check(BOX, PORT, "86GZ", NOW).kind);
    }

    @Test public void aPhoneWithNoCertificateVouchesForNothing() throws Exception {
        FakeBox box = new FakeBox();
        assertEquals(VouchClass.Result.NO_CERTIFICATE,
                new VouchClass(phone("student", false), box, TrustCheckTest.vectorTeacherKey()).run(NOW));
        assertEquals(0, box.gives);
        box.code = null;
        assertEquals(VouchClass.Result.NO_CLASS,
                new VouchClass(phone("teacher", true), box, TrustCheckTest.vectorTeacherKey()).run(NOW));
        assertNull(box.vouch);
        assertNotNull(TrustCodec.readRoot(V.optString("root")));
    }
}
