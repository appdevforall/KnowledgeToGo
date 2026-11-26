package org.appdevforall.k2go.school.trust.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.school.trust.domain.Base64Url;
import org.appdevforall.k2go.school.trust.domain.JoinSchool;
import org.appdevforall.k2go.school.trust.domain.JoinSchool.Result;
import org.appdevforall.k2go.school.trust.domain.RingHost;
import org.appdevforall.k2go.school.trust.domain.RingProof;
import org.appdevforall.k2go.school.trust.domain.RingTestBox;
import org.appdevforall.k2go.school.trust.domain.SchoolRing;
import org.appdevforall.k2go.school.trust.domain.SchoolSetup;
import org.appdevforall.k2go.school.trust.domain.TrustKeys;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Phase 4: device zero approves a teacher, the teacher enrolls a student, through one ring box. */
public class SchoolRingTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = 1798761600L;
    private static final String PW = "Escuela 2027";

    /** A teacher key in memory (the app uses the Android Keystore). */
    private static SchoolRing.TeacherKey memoryKey() {
        String pub = Base64Url.encodeStandard(TrustKeys.generate().getPublic().getEncoded());
        return () -> pub;
    }

    private static SchoolRing.Found ring(TrustRecords.Root root, SchoolRing.Kind kind) {
        return new SchoolRing.Found("K2Go school x", kind, root.school, root.name, "192.168.1.20", 8085);
    }

    @Test public void theHmacValuesAreTheSameAsOnTheBox() {
        // static/dashboard/school/ring.test.ts has the same values.
        assertEquals(RingProof.join("4821", "nonce-abcdefghijkl", null),
                "4be8e17e46082633c787512c730e1c78619490c61d21e1ad1e28c459e130d210");
        assertEquals(RingProof.join("4821", "nonce-abcdefghijkl", "KEYB64=="),
                "e6b74741ce275da051025e8ce7a71204d38026f56a0b6acb2fb167679c3f0b1f");
        assertTrue(RingProof.rootAnswer("4821", "nonce-abcdefghijkl", "ROOT.TOKEN",
                "d101bf72ecede3b5e55d4dde5029032db0f960430c0d90ac464b2702c529580e"));
        assertTrue(RingProof.certAnswer("4821", "nonce-abcdefghijkl", "ROOT.TOKEN", "CERT.TOKEN",
                "4334223e4f4339320550953e685f2538752e0a56aa539ba02248ff3a934d517c"));
        assertFalse(RingProof.rootAnswer("4822", "nonce-abcdefghijkl", "ROOT.TOKEN",
                "d101bf72ecede3b5e55d4dde5029032db0f960430c0d90ac464b2702c529580e"));
        assertTrue(RingProof.newPin().matches("[0-9]{4}"));
        assertTrue(RingProof.newSerial().matches("[0-9a-f]{16}"));
    }

    @Test public void deviceZeroApprovesATeacherWhoEnrollsAStudent() throws Exception {
        RingTestBox wifi = new RingTestBox();
        SchoolTrustFiles zero = new SchoolTrustFiles(tmp.newFolder("zero"));
        assertEquals(SchoolSetup.Result.OK, new SchoolSetup(zero).create("Escuela 1", NOW + 90 * 86400L, PW, PW, NOW));
        TrustRecords.Root root = zero.root();

        // Device zero: the teacher ring needs the school password.
        RingHost host = new RingHost(zero, wifi);
        assertNull(host.open(SchoolRing.Kind.TEACHER, NOW));
        assertFalse(host.unlock("not the password"));
        assertTrue(host.unlock(PW));
        String pin = host.open(SchoolRing.Kind.TEACHER, NOW);
        assertNotNull(pin);

        // A teacher phone joins with a wrong PIN, then with the right one, and waits.
        SchoolTrustFiles teacherFiles = new SchoolTrustFiles(tmp.newFolder("teacher"));
        JoinSchool teacher = new JoinSchool(teacherFiles, wifi, memoryKey());
        String wrong = pin.equals("0000") ? "0001" : "0000";
        assertEquals(Result.WRONG_PIN, teacher.askTeacher(ring(root, SchoolRing.Kind.TEACHER), wrong).result);
        JoinSchool.Step step = teacher.askTeacher(ring(root, SchoolRing.Kind.TEACHER), pin);
        assertEquals(Result.WAITING, step.result);
        assertEquals(Result.WAITING, teacher.collectTeacher(step.pending, NOW));

        // Device zero sees the request and approves it for "Room 3B".
        SchoolRing.View v = host.view();
        assertEquals(1, v.requests.size());
        assertTrue(host.approve(v.requests.get(0), "Room 3B", NOW));
        assertEquals(Result.OK, teacher.collectTeacher(step.pending, NOW));
        assertEquals(root.school, teacherFiles.root().school);
        TrustRecords.Teacher cert = teacherFiles.verifiedCert(teacherFiles.root(), teacherFiles.teacherCertToken(), NOW);
        assertNotNull(cert);
        assertEquals("Room 3B", cert.label);
        host.close();
        assertFalse(host.unlocked());

        // The teacher phone opens a student ring (no school key needed); a student joins.
        RingHost teacherHost = new RingHost(teacherFiles, wifi);
        assertTrue(teacherHost.canEnrollStudents(NOW));
        String studentPin = teacherHost.open(SchoolRing.Kind.STUDENT, NOW);
        assertNotNull(studentPin);
        assertNull(teacherHost.open(SchoolRing.Kind.TEACHER, NOW));   // a teacher cannot approve teachers
        SchoolTrustFiles studentFiles = new SchoolTrustFiles(tmp.newFolder("student"));
        JoinSchool student = new JoinSchool(studentFiles, wifi, memoryKey());
        assertEquals(Result.OK, student.student(ring(root, SchoolRing.Kind.STUDENT), studentPin));
        assertEquals(root.school, studentFiles.root().school);
        assertNull(studentFiles.teacherCertToken());
        assertFalse(new RingHost(studentFiles, wifi).canEnrollStudents(NOW));
    }

    @Test public void aRingWithoutThePinIsRefusedByThePhone() throws Exception {
        RingTestBox wifi = new RingTestBox();
        SchoolTrustFiles zero = new SchoolTrustFiles(tmp.newFolder("zero"));
        new SchoolSetup(zero).create("Escuela 1", NOW + 90 * 86400L, PW, PW, NOW);
        String pin = new RingHost(zero, wifi).open(SchoolRing.Kind.STUDENT, NOW);
        wifi.answerWithWrongMac = true;   // a false ring that does not know the PIN
        SchoolTrustFiles phone = new SchoolTrustFiles(tmp.newFolder("phone"));
        assertEquals(Result.BAD_ANSWER, new JoinSchool(phone, wifi, memoryKey()).student(ring(zero.root(), SchoolRing.Kind.STUDENT), pin));
        assertNull(phone.root());
    }

    @Test public void aPhoneOfAnotherSchoolCannotJoin() throws Exception {
        RingTestBox wifi = new RingTestBox();
        SchoolTrustFiles a = new SchoolTrustFiles(tmp.newFolder("a"));
        new SchoolSetup(a).create("Escuela A", NOW + 90 * 86400L, PW, PW, NOW);
        SchoolTrustFiles b = new SchoolTrustFiles(tmp.newFolder("b"));
        new SchoolSetup(b).create("Escuela B", NOW + 90 * 86400L, PW, PW, NOW);
        String pin = new RingHost(a, wifi).open(SchoolRing.Kind.STUDENT, NOW);
        assertEquals(Result.OTHER_SCHOOL, new JoinSchool(b, wifi, memoryKey()).student(ring(a.root(), SchoolRing.Kind.STUDENT), pin));
    }

    @Test public void deviceZeroRefusesARequest() throws Exception {
        RingTestBox wifi = new RingTestBox();
        SchoolTrustFiles zero = new SchoolTrustFiles(tmp.newFolder("zero"));
        new SchoolSetup(zero).create("Escuela 1", NOW + 90 * 86400L, PW, PW, NOW);
        RingHost host = new RingHost(zero, wifi);
        host.unlock(PW);
        String pin = host.open(SchoolRing.Kind.TEACHER, NOW);
        SchoolTrustFiles t = new SchoolTrustFiles(tmp.newFolder("t"));
        JoinSchool join = new JoinSchool(t, wifi, memoryKey());
        JoinSchool.Step step = join.askTeacher(ring(zero.root(), SchoolRing.Kind.TEACHER), pin);
        assertTrue(host.refuse(host.view().requests.get(0)));
        assertEquals(Result.REFUSED, join.collectTeacher(step.pending, NOW));
        assertNull(t.root());
        // A label longer than a reader takes is not signed.
        JoinSchool.Step again = new JoinSchool(new SchoolTrustFiles(tmp.newFolder("t2")), wifi, memoryKey())
                .askTeacher(ring(zero.root(), SchoolRing.Kind.TEACHER), pin);
        assertFalse(host.approve(host.view().requests.get(1), "A room label that is much longer than forty characters", NOW));
        assertNotNull(again.pending);
    }

    @Test public void deviceZeroCanTeachToo() throws Exception {
        SchoolTrustFiles zero = new SchoolTrustFiles(tmp.newFolder("zero"));
        new SchoolSetup(zero).create("Escuela 1", NOW + 90 * 86400L, PW, PW, NOW);
        RingHost host = new RingHost(zero, new RingTestBox());
        assertFalse(host.selfTeacher(memoryKey(), "Office", NOW));   // locked
        host.unlock(PW);
        assertTrue(host.selfTeacher(memoryKey(), "Office", NOW));
        host.lock();
        assertNotNull(zero.verifiedCert(zero.root(), zero.teacherCertToken(), NOW));
    }
}
