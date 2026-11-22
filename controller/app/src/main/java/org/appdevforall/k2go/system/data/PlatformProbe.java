/*
 * ============================================================================
 * Name        : PlatformProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. The one probe of a box platform page. Home and Settings > Authentication
 *               ask through it, so one rule says what an answer means and every answer lands in
 *               PlatformEvidence.
 * ============================================================================
 */
package org.appdevforall.k2go.system.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.system.domain.PlatformPresence;

import java.net.HttpURLConnection;
import java.net.URL;

public final class PlatformProbe {

    private PlatformProbe() {
    }

    /**
     * ADFA-5061: what the endpoint said, in the model's words. Blocking: call it off the main
     * thread.
     *
     * <p>This used to return dot colours (GREEN, GRAY, AMBER), which is
     * {@code PRESENT}/{@code ABSENT}/{@code NONE} computed identically and named after paint.
     * The sheet then read the paint back out and re-derived the verdict, so one rule existed in
     * three places and the two a user could hit were the copies. It reports evidence now;
     * {@link PlatformPresence} owns what the evidence is worth.
     *
     * <p>K2GO-552: the answer is recorded here, before the caller's view gate. What an endpoint
     * said is a fact about the box, and a caller whose screen went away must not lose it.
     *
     * @param endpoint the card/probe endpoint: "books", "kiwix", "kolibri", "maps", "forgejo", ...
     */
    public static PlatformPresence.Evidence probe(String endpoint) {
        PlatformPresence.Evidence ev = ask(endpoint);
        PlatformEvidence.record(endpoint, ev);
        return ev;
    }

    private static PlatformPresence.Evidence ask(String endpoint) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BoxEndpoints.BASE + "/" + endpoint + "/");
            c = (HttpURLConnection) u.openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) return PlatformPresence.Evidence.PRESENT;
            return code == 404 ? PlatformPresence.Evidence.ABSENT
                    : PlatformPresence.Evidence.NONE;
        } catch (Exception e) {
            return PlatformPresence.Evidence.NONE;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
