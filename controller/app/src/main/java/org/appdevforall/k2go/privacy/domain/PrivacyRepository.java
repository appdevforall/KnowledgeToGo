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

    /** Sets one switch and returns its new state. The box restarts the service to apply it. */
    OutboundSwitch set(String service, boolean outbound) throws PrivacyException;
}
