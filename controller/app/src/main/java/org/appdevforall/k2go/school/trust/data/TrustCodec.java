/*
 * ============================================================================
 * Name        : TrustCodec.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: read and write the JSON bodies of the signed tokens.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import org.appdevforall.k2go.school.trust.domain.SignedToken;
import org.appdevforall.k2go.school.trust.domain.TrustCheck;
import org.appdevforall.k2go.school.trust.domain.TrustKeys;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON for the trust records (the domain has no JSON). Each body has a "kind", so a token of one
 * kind is never read as another kind. read* checks the signature too (TrustCheck): a result that is
 * not null is a record that its signer made.
 */
public final class TrustCodec {

    private TrustCodec() {}

    /** The pinned root record, or null. */
    public static TrustRecords.Root readRoot(String token) {
        SignedToken t = SignedToken.parse(token);
        JSONObject o = body(t, "root");
        if (o == null) return null;
        return TrustCheck.root(t, TrustRecords.Root.of(integer(o, "v"), text(o, "school"), text(o, "name"),
                text(o, "rootKey"), time(o, "until")));
    }

    /** The removed list of this school, or null. */
    public static TrustRecords.Removed readRemoved(TrustRecords.Root root, String token) {
        SignedToken t = SignedToken.parse(token);
        JSONObject o = body(t, "removed");
        if (o == null) return null;
        return TrustCheck.removed(root, t, TrustRecords.Removed.of(integer(o, "v"), text(o, "school"),
                time(o, "issued"), texts(o, "serials")));
    }

    /** The certificate in {@code token} and its verdict. */
    public static final class TeacherCheck {
        public final TrustCheck.Verdict verdict;
        public final TrustRecords.Teacher cert;   // null when MALFORMED

        TeacherCheck(TrustCheck.Verdict verdict, TrustRecords.Teacher cert) {
            this.verdict = verdict;
            this.cert = cert;
        }
    }

    public static TeacherCheck checkTeacher(TrustRecords.Root root, String token, TrustRecords.Removed removed, long now) {
        SignedToken t = SignedToken.parse(token);
        JSONObject o = body(t, "teacher");
        TrustRecords.Teacher cert = o == null ? null : TrustRecords.Teacher.of(integer(o, "v"), text(o, "school"),
                text(o, "key"), text(o, "label"), text(o, "serial"), time(o, "from"), time(o, "until"));
        return new TeacherCheck(TrustCheck.teacher(root, t, cert, removed, now), cert);
    }

    /** Device zero: a certificate for one teacher phone. It refuses fields that a reader refuses. */
    public static String signTeacher(PrivateKey rootKey, String school, String teacherKeySpkiB64, String label,
                                     String serial, long from, long until) {
        if (TrustRecords.Teacher.of(TrustRecords.VERSION, school, teacherKeySpkiB64, label, serial, from, until) == null) {
            throw new IllegalArgumentException("certificate field not valid");
        }
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "teacher").put("v", TrustRecords.VERSION).put("school", school).put("key", teacherKeySpkiB64)
                    .put("label", label).put("serial", serial).put("from", from).put("until", until);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return sign(rootKey, o);
    }

    /** Device zero: the list of removed teacher certificates. It refuses a list that a reader refuses. */
    public static String signRemoved(PrivateKey rootKey, String school, long issued, List<String> serials) {
        if (TrustRecords.Removed.of(TrustRecords.VERSION, school, issued, serials) == null) {
            throw new IllegalArgumentException("removed list not valid");
        }
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "removed").put("v", TrustRecords.VERSION).put("school", school).put("issued", issued)
                    .put("serials", new JSONArray(serials));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return sign(rootKey, o);
    }

    private static String sign(PrivateKey key, JSONObject o) {
        byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
        String token = SignedToken.of(body, TrustKeys.sign(key, body)).compact();
        if (token.length() > SignedToken.MAX_LENGTH) throw new IllegalArgumentException("token too long");
        return token;
    }

    private static JSONObject body(SignedToken t, String kind) {
        if (t == null) return null;
        try {
            JSONObject o = new JSONObject(new String(t.body, StandardCharsets.UTF_8));
            return kind.equals(o.opt("kind")) ? o : null;
        } catch (Exception e) {
            return null;   // not JSON, not an object, or a repeated key
        }
    }

    private static String text(JSONObject o, String name) {
        Object x = o.opt(name);
        return x instanceof String ? (String) x : null;
    }

    /** Only a JSON integer: 1.5 or "1" is not one. */
    private static Long time(JSONObject o, String name) {
        Object x = o.opt(name);
        return x instanceof Integer || x instanceof Long ? ((Number) x).longValue() : null;
    }

    private static Integer integer(JSONObject o, String name) {
        Object x = o.opt(name);
        return x instanceof Integer ? (Integer) x : null;
    }

    private static List<String> texts(JSONObject o, String name) {
        Object x = o.opt(name);
        if (!(x instanceof JSONArray)) return null;
        JSONArray a = (JSONArray) x;
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            Object s = a.opt(i);
            if (!(s instanceof String)) return null;
            out.add((String) s);
        }
        return out;
    }
}
