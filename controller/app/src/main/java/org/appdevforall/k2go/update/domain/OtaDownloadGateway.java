/*
 * ============================================================================
 * Name        : OtaDownloadGateway.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain port: query progress of / cancel an OTA download.
 * ============================================================================
 */
package org.appdevforall.k2go.update.domain;

/** Port over the platform download mechanism: query progress and cancel by id. */
public interface OtaDownloadGateway {
    DownloadProgress query(long downloadId);
    void cancel(long downloadId);
}
