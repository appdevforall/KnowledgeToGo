/*
 * ============================================================================
 * Name        : VouchClass.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: a teacher phone vouches for the class that its box opened.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

/**
 * The teacher key is in the Android Keystore: the box cannot sign with it. The box makes a class key
 * for each class; the teacher phone signs a vouch (school, serial, class code, class key, until) with
 * its teacher key and gives it to the box with its certificate (static/dashboard/school/vouch.ts).
 * The box owns "is the open class vouched for": the app asks it and keeps no copy.
 */
public final class VouchClass {

    /** A vouch is valid for the time of a class at most (MAX_VOUCH_S in the box). */
    public static final long VOUCH_SECONDS = 12L * 3600;

    /** The class key of the open class on this phone's box. */
    public interface Box {
        /** BLOCKS. null: no answer. */
        Key classKey();

        /** BLOCKS. false: the box did not take the vouch. */
        boolean give(String code, String cert, String vouch);

        final class Key {
            /** null: no class is open. */
            public final String code;
            public final String classKey;
            public final boolean vouched;

            public Key(String code, String classKey, boolean vouched) {
                this.code = code;
                this.classKey = classKey;
                this.vouched = vouched;
            }
        }
    }

    public enum Result {
        /** The box has a vouch for its open class. */
        VOUCHED,
        /** This phone is not a teacher phone of a school: its classes are not verified (as before). */
        NO_CERTIFICATE,
        /** No class is open on the box. */
        NO_CLASS,
        FAILED
    }

    private final SchoolTrustRepository repo;
    private final Box box;
    private final SchoolRing.TeacherKey key;

    public VouchClass(SchoolTrustRepository repo, Box box, SchoolRing.TeacherKey key) {
        this.repo = repo;
        this.box = box;
        this.key = key;
    }

    /** BLOCKS. {@code now} in seconds. */
    public Result run(long now) {
        TrustRecords.Teacher cert = PhoneRole.ownCert(repo, key, now);
        if (cert == null) return Result.NO_CERTIFICATE;
        Box.Key k = box.classKey();
        if (k == null) return Result.FAILED;
        if (k.code == null) return Result.NO_CLASS;
        if (k.vouched) return Result.VOUCHED;
        if (k.classKey == null) return Result.FAILED;
        TrustRecords.Root root = repo.root();
        long until = Math.min(now + VOUCH_SECONDS, root == null ? cert.until : Math.min(cert.until, root.until));
        String vouch = repo.classVouch(key, cert, k.code, k.classKey, until);
        if (vouch == null) return Result.FAILED;
        return box.give(k.code, repo.teacherCertToken(), vouch) ? Result.VOUCHED : Result.FAILED;
    }
}
