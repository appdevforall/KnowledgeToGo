/*
 * ============================================================================
 * Name        : ServerReachability.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain port: can a local server URL be reached? Pure abstraction
 *               (no Android / no networking framework) so the dashboard status
 *               ViewModel depends on Domain, not on the data source.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.domain;

public interface ServerReachability {
    boolean isReachable(String url);
}
