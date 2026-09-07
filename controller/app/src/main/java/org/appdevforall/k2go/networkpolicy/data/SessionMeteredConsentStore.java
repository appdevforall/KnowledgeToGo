package org.appdevforall.k2go.networkpolicy.data;

import org.appdevforall.k2go.networkpolicy.domain.MeteredConsentStore;

/**
 * In-memory, process-lifetime consent (ADR-395). Not persisted on purpose: the
 * grant must not outlive the session, so cost awareness returns on the next
 * launch. The metered-network observer clears it the moment the network returns
 * to non-metered, so the grant never outlives the metered episode either.
 */
public final class SessionMeteredConsentStore implements MeteredConsentStore {

    private static final SessionMeteredConsentStore INSTANCE = new SessionMeteredConsentStore();

    public static SessionMeteredConsentStore get() {
        return INSTANCE;
    }

    private SessionMeteredConsentStore() {}

    private volatile boolean granted = false;

    @Override
    public boolean isGranted() {
        return granted;
    }

    @Override
    public void grant() {
        granted = true;
    }

    @Override
    public void clear() {
        granted = false;
    }
}
