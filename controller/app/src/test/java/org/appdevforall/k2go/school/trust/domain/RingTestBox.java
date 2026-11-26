package org.appdevforall.k2go.school.trust.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A box in memory with the rules of static/dashboard/school/ring.ts (one ring, PIN proofs, teacher
 * requests). The phones of a test share one instance, as they share the Wi-Fi.
 */
public final class RingTestBox implements SchoolRing.Box {

    private SchoolRing.Kind kind;
    private String pin;
    private String root;
    private final Map<String, String[]> requests = new LinkedHashMap<>();   // id -> {nonce, key, cert, refused}
    private int nextId;
    public boolean answerWithWrongMac;

    @Override public boolean open(SchoolRing.Kind k, String p, String rootToken) {
        kind = k;
        pin = p;
        root = rootToken;
        requests.clear();
        return true;
    }

    public String pin() { return pin; }

    @Override public SchoolRing.View view() {
        List<SchoolRing.Request> list = new ArrayList<>();
        for (Map.Entry<String, String[]> e : requests.entrySet()) {
            list.add(new SchoolRing.Request(e.getKey(), e.getValue()[1], e.getValue()[2] != null, e.getValue()[3] != null));
        }
        return new SchoolRing.View(kind != null, 600000, null, list);
    }

    @Override public boolean answer(String id, String cert) {
        String[] r = requests.get(id);
        if (r == null) return false;
        if (cert == null) r[3] = "refused";
        else r[2] = cert;
        return true;
    }

    @Override public void close() {
        kind = null;
        requests.clear();
    }

    @Override public SchoolRing.Reply join(SchoolRing.Found ring, String nonce, String key, String proof) {
        if (kind == null) return SchoolRing.Reply.of(SchoolRing.Reply.Status.NO_RING);
        if (kind == SchoolRing.Kind.STUDENT) {
            if (!RingProof.mac(pin, "k2go-ring-join", nonce).equals(proof)) return SchoolRing.Reply.of(SchoolRing.Reply.Status.WRONG_PIN);
            String mac = answerWithWrongMac ? RingProof.mac("0000", "k2go-ring-root", nonce, root)
                    : RingProof.mac(pin, "k2go-ring-root", nonce, root);
            return new SchoolRing.Reply(SchoolRing.Reply.Status.OK, root, null, mac, null);
        }
        if (key == null || !RingProof.mac(pin, "k2go-ring-join", nonce, key).equals(proof)) {
            return SchoolRing.Reply.of(SchoolRing.Reply.Status.WRONG_PIN);
        }
        String id = String.format("%016x", ++nextId);
        requests.put(id, new String[] {nonce, key, null, null});
        return new SchoolRing.Reply(SchoolRing.Reply.Status.WAITING, null, null, null, id);
    }

    @Override public SchoolRing.Reply result(SchoolRing.Found ring, String id, String nonce) {
        String[] r = requests.get(id);
        if (kind == null || r == null || !r[0].equals(nonce)) return SchoolRing.Reply.of(SchoolRing.Reply.Status.NO_RING);
        if (r[3] != null) return SchoolRing.Reply.of(SchoolRing.Reply.Status.REFUSED);
        if (r[2] == null) return SchoolRing.Reply.of(SchoolRing.Reply.Status.WAITING);
        return new SchoolRing.Reply(SchoolRing.Reply.Status.OK, root, r[2], RingProof.mac(pin, "k2go-ring-cert", nonce, root, r[2]), null);
    }
}
