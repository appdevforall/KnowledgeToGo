/*
 * ============================================================================
 * Name        : KeepBoxOfflineUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-588. With Keep offline, no box service may contact an outside server.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

import java.util.ArrayList;
import java.util.List;

/** Rule: while the app is kept offline, every installed service has its outbound switch off. The
 *  box keeps the state in each service's config, so this writes off where the box reports on, and
 *  the box marks the service pending; {@link ApplyPendingRestartsUseCase} restarts it. */
public final class KeepBoxOfflineUseCase {

    private final PrivacyRepository repository;

    public KeepBoxOfflineUseCase(PrivacyRepository repository) {
        this.repository = repository;
    }

    /** Returns the services it switched off. A service that fails does not stop the others; the
     *  first failure is thrown after all of them were tried, so the caller can retry later. */
    public List<String> execute() throws PrivacyException {
        List<String> switched = new ArrayList<>();
        PrivacyException first = null;
        for (OutboundSwitch s : repository.list()) {
            if (s.installed() && s.outbound()) {
                try {
                    repository.set(s.service(), false);
                    switched.add(s.service());
                } catch (PrivacyException e) {
                    if (first == null) first = e;
                }
            }
        }
        if (first != null) throw first;
        return switched;
    }
}
