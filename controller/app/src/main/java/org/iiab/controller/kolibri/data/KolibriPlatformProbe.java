/*
 * ============================================================================
 * Name        : KolibriPlatformProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Whether the Kolibri app itself is installed on this box.
 *               Blocking; call from an IO thread (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import org.iiab.controller.config.BoxEndpoints;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Asks the box whether Kolibri is there, the same way the Get More hub decides
 * whether to draw the Courses card: its endpoint either answers or it does not.
 *
 * <p>This is a distinct question from "is a system installed". The rootfs carries
 * software, not content, and the tier decides which platforms come with it — Basic
 * carries neither Courses nor Books. Content for a platform that was never
 * installed is not deferrable and not startable; it is simply not on offer, which
 * is what {@code OperationDispatcher} answers with {@code UNAVAILABLE}.
 *
 * <p>Written explicitly rather than inferred from "the user got here somehow". The
 * Get More hub only draws a card whose endpoint answered, so arriving from there
 * does imply the platform is present — but that is precisely the kind of implicit
 * derivation ADR-5061 exists to remove.
 *
 * <p>A probe failure reads as absent. That is wrong when the box is merely off, and
 * deliberately so at this layer: the caller has the {@code installed} and
 * {@code serverUp} facts to tell the two apart, and this class is not the place to
 * guess.
 */
public final class KolibriPlatformProbe {

    private static final String URL_PATH = BoxEndpoints.BASE + "/kolibri/";
    private static final int TIMEOUT_MS = 1500;

    private KolibriPlatformProbe() {
    }

    /** True when Kolibri answers on the box. Blocking. */
    public static boolean isPresent() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL_PATH).openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
