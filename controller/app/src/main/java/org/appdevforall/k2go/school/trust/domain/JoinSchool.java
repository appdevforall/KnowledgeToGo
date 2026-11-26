/*
 * ============================================================================
 * Name        : JoinSchool.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: a phone joins its school in a setup ring.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

/**
 * Student ring: the phone pins the root of the ring. Teacher ring: the phone sends the public key of
 * its teacher key (Android Keystore), waits for device zero, then pins the root and keeps the
 * certificate. Each answer must carry the MAC of the PIN, and a phone keeps one school only.
 */
public final class JoinSchool {

    public enum Result { OK, WAITING, WRONG_PIN, TOO_MANY_TRIES, REFUSED, NO_RING, BAD_ANSWER, OTHER_SCHOOL, NO_KEY, FAILED }

    /** A teacher request that waits for device zero. */
    public static final class Pending {
        public final SchoolRing.Found ring;
        final String pin;
        final String nonce;
        final String id;
        /** The public key that this phone sent (device zero shows the start of its fingerprint). */
        public final String key;

        Pending(SchoolRing.Found ring, String pin, String nonce, String id, String key) {
            this.ring = ring;
            this.pin = pin;
            this.nonce = nonce;
            this.id = id;
            this.key = key;
        }
    }

    /** The result of a teacher join: WAITING comes with the request to ask again. */
    public static final class Step {
        public final Result result;
        public final Pending pending;

        Step(Result result, Pending pending) {
            this.result = result;
            this.pending = pending;
        }
    }

    private final SchoolTrustRepository repo;
    private final SchoolRing.Box box;
    private final SchoolRing.TeacherKey teacherKey;

    public JoinSchool(SchoolTrustRepository repo, SchoolRing.Box box, SchoolRing.TeacherKey teacherKey) {
        this.repo = repo;
        this.box = box;
        this.teacherKey = teacherKey;
    }

    /** One school per phone: a phone of another school cannot join this ring. */
    public boolean otherSchool(SchoolRing.Found ring) {
        TrustRecords.Root pinned = repo.root();
        return pinned != null && !pinned.school.equals(ring.school);
    }

    public Result student(SchoolRing.Found ring, String pin) {
        if (otherSchool(ring)) return Result.OTHER_SCHOOL;
        String nonce = RingProof.newNonce();
        SchoolRing.Reply r = box.join(ring, nonce, null, RingProof.join(pin, nonce, null));
        if (r.status != SchoolRing.Reply.Status.OK) return of(r.status);
        if (!RingProof.rootAnswer(pin, nonce, r.root, r.mac)) return Result.BAD_ANSWER;
        return pin(ring, r.root);
    }

    public Step askTeacher(SchoolRing.Found ring, String pin) {
        if (otherSchool(ring)) return new Step(Result.OTHER_SCHOOL, null);
        String key = teacherKey.publicKey();
        if (key == null) return new Step(Result.NO_KEY, null);
        String nonce = RingProof.newNonce();
        SchoolRing.Reply r = box.join(ring, nonce, key, RingProof.join(pin, nonce, key));
        if (r.status != SchoolRing.Reply.Status.WAITING || r.id == null) {
            return new Step(r.status == SchoolRing.Reply.Status.WAITING ? Result.FAILED : of(r.status), null);
        }
        return new Step(Result.WAITING, new Pending(ring, pin, nonce, r.id, key));
    }

    /** Asks the ring for the answer of device zero. WAITING: ask again later. */
    public Result collectTeacher(Pending p, long now) {
        SchoolRing.Reply r = box.result(p.ring, p.id, p.nonce);
        if (r.status != SchoolRing.Reply.Status.OK) return of(r.status);
        if (!RingProof.certAnswer(p.pin, p.nonce, r.root, r.cert, r.mac)) return Result.BAD_ANSWER;
        TrustRecords.Root root = repo.readRoot(r.root);
        if (root == null || !root.school.equals(p.ring.school)) return Result.BAD_ANSWER;
        TrustRecords.Teacher cert = repo.verifiedCert(root, r.cert, now);
        if (cert == null || !cert.key.equals(p.key)) return Result.BAD_ANSWER;
        if (otherSchool(p.ring)) return Result.OTHER_SCHOOL;
        return repo.pinRoot(r.root) && repo.saveTeacherCert(r.cert) ? Result.OK : Result.FAILED;
    }

    private Result pin(SchoolRing.Found ring, String token) {
        TrustRecords.Root root = repo.readRoot(token);
        if (root == null || !root.school.equals(ring.school)) return Result.BAD_ANSWER;
        if (otherSchool(ring)) return Result.OTHER_SCHOOL;
        return repo.pinRoot(token) ? Result.OK : Result.FAILED;
    }

    private static Result of(SchoolRing.Reply.Status s) {
        switch (s) {
            case WAITING: return Result.WAITING;
            case WRONG_PIN: return Result.WRONG_PIN;
            case TOO_MANY_TRIES: return Result.TOO_MANY_TRIES;
            case REFUSED: return Result.REFUSED;
            case NO_RING: return Result.NO_RING;
            case OK: return Result.OK;
            default: return Result.FAILED;
        }
    }
}
