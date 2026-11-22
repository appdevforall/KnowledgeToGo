/*
 * ============================================================================
 * Name        : AuthServices.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. The box services whose admin password K2Go manages, in display order.
 *               One list for Settings > Authentication and the start notice: a new service is
 *               added here (plus its dash-node support and its screen name).
 * ============================================================================
 */
package org.appdevforall.k2go.auth.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AuthServices {
    private AuthServices() {}

    public static final List<String> ALL =
            Collections.unmodifiableList(Arrays.asList("calibre", "kolibri", "forgejo"));

    /** The product name: a proper name, the same in every language. */
    public static String productName(String id) {
        switch (id) {
            case "calibre": return "Calibre-Web";
            case "kolibri": return "Kolibri";
            case "forgejo": return "Forgejo";
            default: return id;
        }
    }
}
