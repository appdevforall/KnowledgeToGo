/*
 * ============================================================================
 * Name        : TrustCheck.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: check a root, a certificate, a removed list, a class proof.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

/**
 * The rules of the school trust (design: school-trust-design.md). The box has the same rules in
 * static/dashboard/school/trust.ts; the shared vectors (trust-vectors.json) keep them equal.
 * Each method takes the token and the record that the data layer read from its body (null: the
 * body is not a valid record of that kind).
 */
public final class TrustCheck {

    /** The result of a teacher certificate check. */
    public enum Verdict { VERIFIED, MALFORMED, OTHER_SCHOOL, BAD_SIGNATURE, NOT_YET, EXPIRED, REMOVED }

    private TrustCheck() {}

    /** The root record when it is signed by its own key and its school id comes from that key. */
    public static TrustRecords.Root root(SignedToken token, TrustRecords.Root record) {
        if (token == null || record == null) return null;
        byte[] spki = record.keySpki();
        if (spki == null || !TrustKeys.schoolId(spki).equals(record.school)) return null;
        return TrustKeys.verify(spki, token.body, token.sig) ? record : null;
    }

    /** The removed list when the root of this school signed it. */
    public static TrustRecords.Removed removed(TrustRecords.Root root, SignedToken token, TrustRecords.Removed record) {
        if (root == null || token == null || record == null || !root.school.equals(record.school)) return null;
        return TrustKeys.verify(root.keySpki(), token.body, token.sig) ? record : null;
    }

    /** A teacher certificate against the pinned root, the removed list and the time (seconds). */
    public static Verdict teacher(TrustRecords.Root root, SignedToken token, TrustRecords.Teacher cert,
                                  TrustRecords.Removed removed, long now) {
        if (root == null || token == null || cert == null) return Verdict.MALFORMED;
        if (!cert.school.equals(root.school)) return Verdict.OTHER_SCHOOL;
        if (!TrustKeys.verify(root.keySpki(), token.body, token.sig)) return Verdict.BAD_SIGNATURE;
        if (now < cert.from) return Verdict.NOT_YET;
        if (now >= Math.min(cert.until, root.until)) return Verdict.EXPIRED;   // the root year ends it too
        if (removed != null && removed.serials.contains(cert.serial)) return Verdict.REMOVED;
        return Verdict.VERIFIED;
    }

    /**
     * The class vouch when the teacher key of {@code cert} signed it, for the school and the serial of
     * the certificate, and it is not past its time (seconds).
     */
    public static TrustRecords.ClassVouch vouch(TrustRecords.Teacher cert, SignedToken token,
                                                TrustRecords.ClassVouch record, long now) {
        if (cert == null || token == null || record == null) return null;
        if (!record.school.equals(cert.school) || !record.serial.equals(cert.serial) || now >= record.until) return null;
        return TrustKeys.verify(cert.keySpki(), token.body, token.sig) ? record : null;
    }

    /**
     * The teacher box holds the private key {@code keySpki} (the class key of a vouch), for this nonce,
     * this class code and this box address (the address that the student phone used).
     */
    public static boolean proof(byte[] keySpki, String nonce, String code, String host, String proof) {
        if (keySpki == null || proof == null || proof.length() > 200) return false;
        byte[] data = TrustKeys.proofBytes(nonce, code, host);
        byte[] sig = Base64Url.decode(proof);
        return data != null && sig != null && TrustKeys.verify(keySpki, data, sig);
    }
}
