/*
 * ============================================================================
 * Name        : TrustRecords.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the root, the teacher certificate, the removed list, the class vouch.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The signed bodies and the rule for valid fields. The data layer reads the JSON and calls
 * the factories; a factory gives null for a field that is not valid. The box has the same limits
 * (static/dashboard/school/trust.ts).
 */
public final class TrustRecords {

    public static final int VERSION = 1;
    private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");
    private static final int MAX_KEY = 200;
    /** A removed list must fit in one token (SignedToken.MAX_LENGTH): about 25 chars per serial. */
    public static final int MAX_SERIALS = 120;

    private TrustRecords() {}

    static boolean isTime(Long t) { return t != null && t >= 0 && t <= 9007199254740991L; }

    static boolean isText(String s, int max) { return s != null && s.length() <= max; }

    /** The root record that a phone pins. Signed by its own key. */
    public static final class Root {
        public final String school;
        public final String name;
        public final String rootKey;   // SPKI DER, standard base64
        public final long until;

        private Root(String school, String name, String rootKey, long until) {
            this.school = school;
            this.name = name;
            this.rootKey = rootKey;
            this.until = until;
        }

        public static Root of(Integer v, String school, String name, String rootKey, Long until) {
            if (v == null || v != VERSION || !isText(school, 16) || !HEX16.matcher(school).matches()
                    || !isText(name, 80) || !isText(rootKey, MAX_KEY) || !isTime(until)) return null;
            return new Root(school, name, rootKey, until);
        }

        public byte[] keySpki() { return Base64Url.decodeStandard(rootKey); }
    }

    /** The certificate of one teacher phone, signed by the root. label: the room. */
    public static final class Teacher {
        public final String school;
        public final String key;   // SPKI DER, standard base64
        public final String label;
        public final String serial;
        public final long from;
        public final long until;

        private Teacher(String school, String key, String label, String serial, long from, long until) {
            this.school = school;
            this.key = key;
            this.label = label;
            this.serial = serial;
            this.from = from;
            this.until = until;
        }

        public static Teacher of(Integer v, String school, String key, String label, String serial, Long from, Long until) {
            if (v == null || v != VERSION || !isText(school, 16) || !isText(key, MAX_KEY) || !isText(label, 40)
                    || !isText(serial, 16) || !HEX16.matcher(serial).matches() || !isTime(from) || !isTime(until)) return null;
            return new Teacher(school, key, label, serial, from, until);
        }

        public byte[] keySpki() { return Base64Url.decodeStandard(key); }
    }

    /**
     * A teacher phone vouches for one class: its teacher key signs the class code and the class key that
     * its box made for this class. The box signs each class proof with the class key, so the teacher key
     * never leaves the Android Keystore. Same limits as the box (static/dashboard/school/trust.ts).
     */
    public static final class ClassVouch {
        public final String school;
        public final String serial;
        public final String code;
        public final String classKey;   // SPKI DER, standard base64
        public final long until;

        private ClassVouch(String school, String serial, String code, String classKey, long until) {
            this.school = school;
            this.serial = serial;
            this.code = code;
            this.classKey = classKey;
            this.until = until;
        }

        public static ClassVouch of(Integer v, String school, String serial, String code, String classKey, Long until) {
            if (v == null || v != VERSION || !isText(school, 16) || !HEX16.matcher(school).matches()
                    || !isText(serial, 16) || !HEX16.matcher(serial).matches() || !TrustKeys.isProofField(code)
                    || !isText(classKey, MAX_KEY) || !isTime(until)) return null;
            return new ClassVouch(school, serial, code, classKey, until);
        }

        public byte[] keySpki() { return Base64Url.decodeStandard(classKey); }
    }

    /** The serials of the removed teacher certificates, signed by the root. */
    public static final class Removed {
        public final String school;
        public final long issued;
        public final List<String> serials;   // sorted, no repeats

        private Removed(String school, long issued, TreeSet<String> serials) {
            this.school = school;
            this.issued = issued;
            this.serials = Collections.unmodifiableList(Arrays.asList(serials.toArray(new String[0])));
        }

        public static Removed of(Integer v, String school, Long issued, List<String> serials) {
            if (v == null || v != VERSION || !isText(school, 16) || !isTime(issued) || serials == null
                    || serials.size() > MAX_SERIALS) return null;
            for (String s : serials) if (s == null || !HEX16.matcher(s).matches()) return null;
            return new Removed(school, issued, new TreeSet<>(serials));
        }

        /**
         * Lists are merged, never replaced: a device zero that comes back from an old backup cannot
         * bring a removed teacher back. issued: the newest of the two.
         */
        public static Removed merge(Removed a, Removed b) {
            if (a == null) return b;
            if (b == null || !a.school.equals(b.school)) return a;
            TreeSet<String> all = new TreeSet<>(a.serials);
            all.addAll(b.serials);
            return new Removed(a.school, Math.max(a.issued, b.issued), all);
        }
    }
}
