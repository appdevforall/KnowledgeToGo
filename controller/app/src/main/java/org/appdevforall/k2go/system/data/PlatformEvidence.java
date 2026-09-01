/*
 * ============================================================================
 * Name        : PlatformEvidence.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5061. The last thing a probe established about each
 *               platform, for as long as the process lives.
 * ============================================================================
 */
package org.appdevforall.k2go.system.data;

import org.appdevforall.k2go.system.domain.PlatformPresence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What we last learned about each platform, remembered past the screen that learned it.
 *
 * <p>This started as a field on the Home card and that was too short a life. {@code
 * populateCards} runs in {@code onCreateView} and builds new cards, so switching to Settings and
 * back forgets everything — and stopping the server is done from Settings, so the one journey
 * that most needs the memory is the one that erased it. On the device a platform known absent by
 * a 404 came back reading "Stopped" like the four that had really been running, and its sheet
 * stopped offering the install it should still offer.
 *
 * <p>Process-scoped and nothing more. A probe answer is a fact about the box, not about a
 * fragment, and the box does not change because a tab did. It is deliberately not persisted:
 * across app launches the rootfs may have been replaced, and a stale "installed" would be worse
 * than asking again.
 *
 * <p>Knowingly incomplete. Action item 10 in ADR-5061 calls for one presence answer for the whole
 * app — six probes, five answers, three staleness policies — with observation times so callers
 * can reason about age. This is the memory half of that, with no timestamps and no policy, added
 * because the shorter-lived version was visibly wrong. Whoever builds the repository should grow
 * this rather than add a seventh place to ask.
 */
public final class PlatformEvidence {

    /** Keyed by the card/probe endpoint: "books", "kiwix", "kolibri", "maps", "code". */
    private static final Map<String, PlatformPresence.Evidence> LAST = new ConcurrentHashMap<>();

    private PlatformEvidence() {
    }

    /** Record what an endpoint just said. */
    public static void record(String endpoint, PlatformPresence.Evidence evidence) {
        if (endpoint == null || endpoint.isEmpty() || evidence == null) {
            return;
        }
        LAST.put(endpoint, evidence);
    }

    /** The last answer for this endpoint, or null when nothing has been established yet. */
    public static PlatformPresence.Evidence last(String endpoint) {
        return endpoint == null ? null : LAST.get(endpoint);
    }

    /**
     * Forget everything, for when the box is replaced.
     *
     * <p>Not wired to the destructive routes yet: nothing reads this across a reinstall today,
     * because the screens that use it re-probe within seconds of the system coming back. Left
     * here so the answer to "who clears it" is written down rather than discovered.
     */
    public static void clear() {
        LAST.clear();
    }
}
