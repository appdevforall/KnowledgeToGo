/*
 * ============================================================================
 * Name        : RestReadiness.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4874. Single definition of the dashboard REST readiness probe, shared by the
 *               post-install provisioning paths (SetupProgressActivity, LibraryHomeFragment). The
 *               engine (dash-node) is considered up when an /api call returns non-5xx — unlike /home
 *               (nginx), which answers before its upstream is ready. Used to gate the wishlist drain
 *               so we never POST downloads while the server still returns 502/503.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import org.appdevforall.k2go.config.BoxEndpoints;

import java.net.HttpURLConnection;
import java.net.URL;

public final class RestReadiness {
    private RestReadiness() {}

    /** True when the dashboard REST engine answers (non-5xx) an /api request. Blocks; call off the
     *  main thread. 502/503 => the engine is not up yet. */
    public static boolean apiReady() {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BoxEndpoints.API + "/books/library");
            c = (HttpURLConnection) u.openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
