/*
 * ============================================================================
 * Name        : GetOutboundSwitchesUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. The switches the Privacy screen shows, in a fixed order.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rule: show only the services this app can name, in {@link #ORDER}. A newer box server may
 *  report a service that this app does not know yet; it is left out, not shown unnamed. */
public final class GetOutboundSwitchesUseCase {

    static final List<String> ORDER = Arrays.asList(OutboundSwitch.KOLIBRI, OutboundSwitch.FORGEJO);

    private final PrivacyRepository repository;

    public GetOutboundSwitchesUseCase(PrivacyRepository repository) {
        this.repository = repository;
    }

    public List<OutboundSwitch> execute() throws PrivacyException {
        return known(repository.list());
    }

    static List<OutboundSwitch> known(List<OutboundSwitch> reported) {
        List<OutboundSwitch> out = new ArrayList<>();
        for (String id : ORDER) {
            for (OutboundSwitch s : reported) {
                if (id.equals(s.service())) { out.add(s); break; }
            }
        }
        return out;
    }
}
