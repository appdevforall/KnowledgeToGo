/*
 * ============================================================================
 * Name        : PrivacyRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. Domain port for the outbound switches of the box services.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

import java.util.List;

/** The box owns the state (each service's own config); this port only reads and sets it.
 *  Blocking: call off the main thread. */
public interface PrivacyRepository {

    /** The switches the box reports. */
    List<OutboundSwitch> list() throws PrivacyException;

    /** Writes one switch in the service config. Returns true when the value changed. */
    boolean set(String service, boolean outbound) throws PrivacyException;

    /** Asks the box to restart one service (the in-place per-service restart, ADR-5343a). */
    void requestRestart(String service) throws PrivacyException;
}
