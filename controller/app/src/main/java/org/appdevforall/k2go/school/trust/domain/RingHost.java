/*
 * ============================================================================
 * Name        : RingHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the phone that opens a setup ring.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.security.PrivateKey;
import java.util.Arrays;

/**
 * Device zero opens the teacher ring and signs the certificates; device zero or a teacher phone opens
 * the student ring (no signature: a phone with a root and no certificate is a student). The school
 * key is open in memory only while a teacher ring runs; close() lets it go.
 */
public final class RingHost {

    private final SchoolTrustRepository repo;
    private final SchoolRing.Box box;
    private final SchoolRing.TeacherKey teacherKey;
    private PrivateKey key;   // device zero, teacher ring only

    public RingHost(SchoolTrustRepository repo, SchoolRing.Box box, SchoolRing.TeacherKey teacherKey) {
        this.repo = repo;
        this.box = box;
        this.teacherKey = teacherKey;
    }

    /** Device zero: the school password opens the school key. false: wrong password or no key. */
    public boolean unlock(String password) {
        TrustRecords.Root root = repo.root();
        KeyVault.Sealed vault = repo.vault();
        if (root == null || vault == null) return false;
        byte[] pkcs8 = KeyVault.open(vault, password, root.school);
        if (pkcs8 == null) return false;
        try {
            PrivateKey k = TrustKeys.privateKey(pkcs8);
            if (!TrustKeys.isPair(k, root.keySpki())) return false;
            key = k;
            return true;
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    /** A phone may open a student ring when it is device zero or a teacher phone (PhoneRole). */
    public boolean canEnrollStudents(long now) {
        if (repo.root() == null) return false;
        return PhoneRole.isDeviceZero(repo) || PhoneRole.ownCert(repo, teacherKey, now) != null;
    }

    /** Opens a ring on the box of this phone. The PIN for the screen and the board, or null. */
    public String open(SchoolRing.Kind kind, long now) {
        if (kind == SchoolRing.Kind.TEACHER ? key == null : !canEnrollStudents(now)) return null;
        String token = repo.rootTokenText();
        if (token == null) return null;
        String pin = RingProof.newPin();
        return box.open(kind, pin, token) ? pin : null;
    }

    public SchoolRing.View view() {
        return box.view();
    }

    /** Device zero approves one teacher phone. label: the room. */
    public boolean approve(SchoolRing.Request r, String label, long now) {
        TrustRecords.Root root = repo.root();
        if (key == null || root == null || r == null) return false;
        try {
            String cert = repo.teacherCert(key, root, r.key, label, RingProof.newSerial(), now, root.until);
            return box.answer(r.id, cert);
        } catch (IllegalArgumentException e) {
            return false;   // a label that a reader refuses (too long)
        }
    }

    public boolean refuse(SchoolRing.Request r) {
        return r != null && box.answer(r.id, null);
    }

    /** Device zero is a teacher phone too (a small school): it signs a certificate for its own key. */
    public boolean selfTeacher(SchoolRing.TeacherKey teacherKey, String label, long now) {
        TrustRecords.Root root = repo.root();
        String pub = teacherKey.publicKey();
        if (key == null || root == null || pub == null) return false;
        try {
            String cert = repo.teacherCert(key, root, pub, label, RingProof.newSerial(), now, root.until);
            return repo.saveTeacherCert(cert);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean unlocked() { return key != null; }

    /** Lets the school key go, with no ring change. */
    public void lock() { key = null; }

    /** Closes the ring and lets the school key go. */
    public void close() {
        key = null;
        box.close();
    }
}
