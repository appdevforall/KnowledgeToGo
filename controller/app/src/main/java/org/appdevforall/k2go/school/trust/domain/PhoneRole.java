/*
 * ============================================================================
 * Name        : PhoneRole.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the role of this phone, derived from its trust files.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import org.appdevforall.k2go.school.domain.SchoolRole;

/**
 * The role is not stored (design 5, ENG-1): the trust files are the one owner. No root file: no
 * role (as before the school trust). A root and a verified certificate for the teacher key of this
 * phone: teacher. Else: student. A root file that is there but not valid gives a student: a file of
 * the school always takes rights away. The box derives the same role from the same files
 * (static/dashboard/school/role.ts); it cannot see the Keystore, so it does not check the key.
 */
public final class PhoneRole {

    private PhoneRole() {}

    /** BLOCKS (files, Keystore). {@code now} in seconds. */
    public static SchoolRole of(SchoolTrustRepository repo, SchoolRing.TeacherKey key, long now) {
        if (repo.rootTokenText() == null) return SchoolRole.NONE;
        return ownCert(repo, key, now) != null ? SchoolRole.TEACHER : SchoolRole.STUDENT;
    }

    /** Device zero keeps the sealed school key. It approves teachers; it teaches only with a certificate. */
    public static boolean isDeviceZero(SchoolTrustRepository repo) {
        return repo.vault() != null;
    }

    /**
     * The verified certificate of this phone: signed by the pinned root, valid now, and for the teacher
     * key of this phone (a certificate copied from another phone does not count). null: none.
     */
    public static TrustRecords.Teacher ownCert(SchoolTrustRepository repo, SchoolRing.TeacherKey key, long now) {
        TrustRecords.Root root = repo.root();
        String token = repo.teacherCertToken();
        if (root == null || token == null) return null;
        TrustRecords.Teacher cert = repo.verifiedCert(root, token, now);
        // Only a phone with a certificate asks the Keystore: a student phone makes no teacher key.
        return cert != null && cert.key.equals(key.publicKey()) ? cert : null;
    }
}
