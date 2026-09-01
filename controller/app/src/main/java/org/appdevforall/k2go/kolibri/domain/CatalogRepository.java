/*
 * ============================================================================
 * Name        : CatalogRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain port for reading the Kolibri channel catalog and topic
 *               trees. Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.domain;

import java.util.List;

/**
 * How the domain obtains channels and topic trees. The Data layer implements it;
 * the domain never learns that one comes from a file in the APK and the other
 * from a remote API.
 *
 * <p>Mirrors {@code RootfsRepository}: implementations must <b>never throw</b>.
 * Failure is expressed in the return value.
 *
 * <p>The asymmetry between the two methods is deliberate and measured. The
 * channel catalog is bundled, so reading it cannot fail in any way worth
 * modelling. The topic tree is fetched from Studio, so it can.
 */
public interface CatalogRepository {

    /**
     * Every channel in the bundled catalog, in catalog order.
     *
     * <p>Always returns a list, possibly empty. Not a network call: Studio's
     * channel endpoint is 97 % base64 thumbnails with no way to opt out, so the
     * catalog is generated at release time instead. See ADR-4954 D1.
     */
    List<Channel> channels();

    /**
     * When the bundled catalog was generated, as an ISO-8601 date, or empty when
     * the asset carries no stamp.
     *
     * <p>Exposed because a bundled catalog goes stale between releases and the
     * screen has to be able to admit it, the way the ZIM flow captions
     * "Estimated sizes (offline)".
     */
    String catalogGeneratedOn();

    /**
     * Reads one level of a channel's topic tree from the live source.
     *
     * <p>Not bundled: the asset carries channels, not trees, and shipping every
     * tree would dwarf the APK. Topic selection therefore needs connectivity
     * even though channel selection does not.
     *
     * @param nodeId the subtree root; a channel's {@link Channel#rootNodeId()} to start
     * @return the node with its direct children, or {@code null} if unreadable
     */
    TopicNode fetchTree(String nodeId);
}
