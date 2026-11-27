/*
 * ============================================================================
 * Name        : SchoolRing.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the ports of the setup ring (the box, the teacher key).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.util.List;

/** The ports and the values of the setup ring (design: school-trust-design.md 6.2, 6.3). */
public final class SchoolRing {

    private SchoolRing() {}

    public enum Kind { TEACHER, STUDENT }

    /** A ring that a phone announces on the Wi-Fi. */
    public static final class Found {
        public final String service;   // the mDNS name: one ring
        public final Kind kind;
        public final String school;    // the school id
        public final String schoolName;
        public final String address;   // IPv4
        public final int port;

        public Found(String service, Kind kind, String school, String schoolName, String address, int port) {
            this.service = service;
            this.kind = kind;
            this.school = school;
            this.schoolName = schoolName;
            this.address = address;
            this.port = port;
        }
    }

    /** A teacher phone that asks device zero for a certificate. */
    public static final class Request {
        public final String id;
        public final String key;   // its public key, SPKI DER base64
        public final boolean approved;
        public final boolean refused;

        public Request(String id, String key, boolean approved, boolean refused) {
            this.id = id;
            this.key = key;
            this.approved = approved;
            this.refused = refused;
        }
    }

    /** The ring of this phone, as its box keeps it. */
    public static final class View {
        public final boolean open;
        public final long closesInMs;
        public final String closedBy;   // "time", "guesses", "host" or null
        public final List<Request> requests;

        public View(boolean open, long closesInMs, String closedBy, List<Request> requests) {
            this.open = open;
            this.closesInMs = closesInMs;
            this.closedBy = closedBy;
            this.requests = requests;
        }
    }

    /** An answer of a ring to a phone that joins. */
    public static final class Reply {
        public enum Status { OK, WAITING, WRONG_PIN, TOO_MANY_TRIES, REFUSED, NO_RING, FAILED }

        public final Status status;
        public final String root;
        public final String cert;
        public final String mac;
        public final String id;

        public Reply(Status status, String root, String cert, String mac, String id) {
            this.status = status;
            this.root = root;
            this.cert = cert;
            this.mac = mac;
            this.id = id;
        }

        public static Reply of(Status s) { return new Reply(s, null, null, null, null); }
    }

    /** The box of this phone (host) and the boxes of other phones (join). */
    public interface Box {
        boolean open(Kind kind, String pin, String rootToken);
        View view();
        boolean answer(String id, String certOrNull);
        void close();
        Reply join(Found ring, String nonce, String teacherKey, String proof);
        Reply result(Found ring, String id, String nonce);
    }

    /** The private key of a teacher phone. It never leaves the phone (Android Keystore). */
    public interface TeacherKey {
        /** The public key, SPKI DER base64; makes the key pair the first time. null: no key store. */
        String publicKey();

        /** An ECDSA P-256 SHA-256 signature (DER) of {@code data} with the teacher key. null: no key. */
        byte[] sign(byte[] data);
    }
}
