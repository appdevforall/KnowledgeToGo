package org.appdevforall.k2go.contentexchange.domain;

import java.util.List;

/**
 * Domain port for reading managed content and storage from the box.
 *
 * <p>The abstraction the domain owns; the Data layer implements it against the
 * core REST API (reached through dash-node: {@code /k2go-api/v1/*}). The domain
 * never learns HOW the data is fetched.
 *
 * <p>Error contract:
 * <ul>
 *   <li>{@link #fetchApps()} and {@link #fetchContent()} back the manager's
 *       essential data; they throw on a real failure (box unreachable), which the
 *       use case / ViewModel turns into an error state.</li>
 *   <li>{@link #fetchStorage()} is optional (the endpoint may not exist yet); it
 *       never throws and returns {@link StorageStatus#unavailable()} on any
 *       failure, so the manager degrades gracefully to "no free-disk figure".</li>
 * </ul>
 */
public interface ContentRepository {

    /**
     * Installed apps that have a loaded adapter on the box ({@code GET /v1/apps}).
     * Drives the group set and ordering, so an installed-but-empty app still shows.
     *
     * @throws Exception when the box cannot be reached.
     */
    List<String> fetchApps() throws Exception;

    /**
     * Every content item on the box ({@code GET /v1/content}).
     *
     * @throws Exception when the box cannot be reached.
     */
    List<ContentItem> fetchContent() throws Exception;

    /**
     * The box storage summary ({@code GET /v1/storage}). Never throws; returns
     * {@link StorageStatus#unavailable()} when the endpoint is missing or fails.
     */
    StorageStatus fetchStorage();
}
