/*
 * ============================================================================
 * Name        : VerifyClass.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: is a class on the Wi-Fi from a teacher of this school?
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

/**
 * A phone with the school root sends a new nonce to the box of each class (design 6.4). The box
 * answers with the certificate of its phone, the class vouch and a proof of the class key. The chain:
 * the root signed the certificate; the teacher key of the certificate signed the vouch for this class
 * code; the class key of the vouch signed (nonce, class code, box address). The box address is the
 * address that this phone used, so a false box that relays the nonce to a real box fails.
 */
public final class VerifyClass {

    /** What the list shows for a class. */
    public enum Kind {
        /** This phone is not part of a school: every class shows as before the school trust. */
        NO_SCHOOL,
        VERIFIED,
        /** No certificate, no vouch, another school, expired, a bad signature: grey, no Join. */
        NOT_VERIFIED,
        /** The box did not answer (no connection, a time-out): grey, no Join, unless it was verified. */
        NO_ANSWER
    }

    public static final class Result {
        public final Kind kind;
        /** The room on the certificate (VERIFIED only), else null. */
        public final String label;

        private Result(Kind kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        public static final Result NO_SCHOOL = new Result(Kind.NO_SCHOOL, null);
        public static final Result NOT_VERIFIED = new Result(Kind.NOT_VERIFIED, null);
        public static final Result NO_ANSWER = new Result(Kind.NO_ANSWER, null);

        /** A phone of a school joins only a class of a teacher of its school (design 6.4). */
        public boolean canJoin() { return kind == Kind.NO_SCHOOL || kind == Kind.VERIFIED; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) return false;
            Result r = (Result) o;
            return kind == r.kind && (label == null ? r.label == null : label.equals(r.label));
        }

        @Override
        public int hashCode() { return kind.hashCode() * 31 + (label == null ? 0 : label.hashCode()); }
    }

    /** The /school/hello answer of a box. */
    public interface Hello {
        /**
         * BLOCKS: one POST to the box at {@code address}:{@code port}. null: no answer (no connection,
         * a time-out). A box that answers with no chain (no vouch) gives an Answer with null fields.
         */
        Answer ask(String address, int port, String nonce);

        final class Answer {
            public final String code;
            public final String cert;
            public final String vouch;
            public final String proof;

            public Answer(String code, String cert, String vouch, String proof) {
                this.code = code;
                this.cert = cert;
                this.vouch = vouch;
                this.proof = proof;
            }
        }
    }

    private final SchoolTrustRepository repo;
    private final Hello hello;

    public VerifyClass(SchoolTrustRepository repo, Hello hello) {
        this.repo = repo;
        this.hello = hello;
    }

    /** BLOCKS. The class {@code code} at the box {@code address}:{@code port}; {@code now} in seconds. */
    public Result check(String address, int port, String code, long now) {
        TrustRecords.Root root = repo.root();
        if (root == null) return repo.rootTokenText() == null ? Result.NO_SCHOOL : Result.NOT_VERIFIED;
        if (address == null || code == null) return Result.NOT_VERIFIED;
        String nonce = RingProof.newNonce();
        Hello.Answer a = hello.ask(address, port, nonce);
        if (a == null) return Result.NO_ANSWER;
        if (!code.equals(a.code)) return Result.NOT_VERIFIED;
        TrustRecords.Teacher cert = repo.verifiedCert(root, a.cert, now);
        if (cert == null) return Result.NOT_VERIFIED;
        TrustRecords.ClassVouch vouch = repo.readVouch(cert, a.vouch, now);
        if (vouch == null || !code.equals(vouch.code)) return Result.NOT_VERIFIED;
        String host = address + ":" + port;
        if (!TrustCheck.proof(vouch.keySpki(), nonce, code, host, a.proof)) return Result.NOT_VERIFIED;
        return new Result(Kind.VERIFIED, cert.label);
    }
}
