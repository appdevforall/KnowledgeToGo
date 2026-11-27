/*
 * ============================================================================
 * Name        : HttpVouchBox.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the class key and the class vouch on this phone's box.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.trust.domain.VouchClass;
import org.json.JSONObject;

/** /school/teacher/vouch answers localhost only: this is this phone's box (dash-node school/vouch.ts). */
public final class HttpVouchBox implements VouchClass.Box {

    private final String url;

    public HttpVouchBox() { this(BoxEndpoints.BASE); }

    HttpVouchBox(String base) { this.url = base + "/school/teacher/vouch"; }   // tests

    @Override
    public Key classKey() {
        BoxJson.Answer a = BoxJson.call("GET", url, null);
        if (a.status != 200 || a.json == null) return null;
        return new Key(BoxJson.str(a.json, "code"), BoxJson.str(a.json, "classKey"), a.json.optBoolean("vouched"));
    }

    @Override
    public boolean give(String code, String cert, String vouch) {
        JSONObject b = new JSONObject();
        try {
            b.put("code", code).put("cert", cert).put("vouch", vouch);
        } catch (Exception e) {
            return false;
        }
        return BoxJson.call("POST", url, b).status == 204;
    }
}
