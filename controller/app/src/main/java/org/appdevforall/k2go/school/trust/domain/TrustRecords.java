/*
 * ============================================================================
 * Name        : TrustRecords.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the root record, the teacher certificate, the removed list.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The three signed bodies and the rule for valid fields. The data layer reads the JSON and calls
 * the factories; a factory gives null for a field that is not valid. The box has the same limits
 * (static/dashboard/school/trust.ts).
 */
public final class TrustRecords {

    public static final int VERSION = 1;
    private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");
    private static final int MAX_KEY = 200;
    private static final int MAX_SERIALS = 500;

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
            if (b == null) return a;
            TreeSet<String> all = new TreeSet<>(a.serials);
            all.addAll(b.serials);
            return new Removed(a.school, Math.max(a.issued, b.issued), all);
        }
    }
}
