package org.appdevforall.k2go.networkpolicy.domain;

/**
 * Holds the one ephemeral fact the gate needs: did the user consent to spend
 * metered data for this session?
 *
 * <p>Ephemeral by design (ADR-395): a persisted "always allow" would defeat the
 * cost-awareness goal, and a persisted grant that nobody clears is the
 * stuck-marker anti-pattern this project avoids. Lifecycle: the consent dialog
 * calls {@link #grant()}; the metered-network observer calls {@link #clear()}
 * when the default network returns to unmetered; process death clears it because
 * the only implementation keeps it in memory.
 */
public interface MeteredConsentStore {

    boolean isGranted();

    void grant();

    void clear();
}
