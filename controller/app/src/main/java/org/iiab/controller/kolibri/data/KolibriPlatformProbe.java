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
import org.iiab.controller.system.domain.PlatformPresence;

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
 * <p>ADFA-5061: this used to return a boolean, and a probe failure read as absent. The
 * javadoc said the caller had the facts to tell "off" from "not installed" apart — and
 * the one caller did not, so a box that was off, busy, slow, or behind a 502 during a
 * restart was indistinguishable from a Basic tier with no Kolibri module. Since the
 * dispatcher treats "absent" as terminal, a courses order asked for while an earlier
 * one was still downloading was refused with "not installed" and discarded.
 *
 * <p>So the answer is now three-valued, and the third value is the honest one: an
 * endpoint that does not reply has not told us anything. Note that a 404 <em>is</em> an
 * answer — nginx replying "no such location" is real evidence of absence, and the Home
 * cards have always distinguished it. Collapsing it with a timeout threw that away.
 */
public final class KolibriPlatformProbe {

    private static final String URL_PATH = BoxEndpoints.BASE + "/kolibri/";
    private static final int TIMEOUT_MS = 1500;

    private KolibriPlatformProbe() {
    }

    /**
     * Asks the box, and reports the evidence rather than a verdict. Blocking.
     *
     * <p>2xx/3xx is present; 404 is absent; everything else establishes nothing — including
     * 5xx, which is what nginx returns while the platform behind it restarts, and a timeout,
     * which is what a busy platform returns. {@link PlatformPresence} decides what each one
     * is worth.
     */
    public static PlatformPresence.Evidence probe() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL_PATH).openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) {
                return PlatformPresence.Evidence.PRESENT;
            }
            return code == 404 ? PlatformPresence.Evidence.ABSENT
                    : PlatformPresence.Evidence.NONE;
        } catch (Exception e) {
            return PlatformPresence.Evidence.NONE;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
