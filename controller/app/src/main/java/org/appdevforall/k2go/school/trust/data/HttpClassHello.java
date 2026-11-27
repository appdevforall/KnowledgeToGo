/*
 * ============================================================================
 * Name        : HttpClassHello.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: asks a teacher box for the chain of its class (/school/hello).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import org.appdevforall.k2go.school.trust.domain.VerifyClass;
import org.json.JSONObject;

/** One POST of a nonce to the box of a class on the Wi-Fi (dash-node school/vouch.ts). */
public final class HttpClassHello implements VerifyClass.Hello {

    @Override
    public Answer ask(String address, int port, String nonce) {
        JSONObject b = new JSONObject();
        try {
            b.put("nonce", nonce);
        } catch (Exception e) {
            return null;
        }
        BoxJson.Answer a = BoxJson.call("POST", "http://" + address + ":" + port + "/school/hello", b);
        if (a.status < 0 || a.status == 429) return null;   // no answer, or ask later (too many hellos)
        if (a.status != 200 || a.json == null) return new Answer(null, null, null, null);   // no chain
        return new Answer(BoxJson.str(a.json, "code"), BoxJson.str(a.json, "cert"), BoxJson.str(a.json, "vouch"),
                BoxJson.str(a.json, "proof"));
    }
}
