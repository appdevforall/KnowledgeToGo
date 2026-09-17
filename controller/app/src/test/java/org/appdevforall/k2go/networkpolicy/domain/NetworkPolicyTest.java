package org.appdevforall.k2go.networkpolicy.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure-JVM tests for the heavy-start decision table. No Android, no network. */
public class NetworkPolicyTest {

    private final NetworkPolicy policy = new NetworkPolicy();

    @Test
    public void unmetered_alwaysAllows() {
        assertEquals(NetworkPolicyDecision.ALLOW, policy.decideHeavyStart(NetworkClass.UNMETERED, false));
        assertEquals(NetworkPolicyDecision.ALLOW, policy.decideHeavyStart(NetworkClass.UNMETERED, true));
    }

    @Test
    public void metered_withoutConsent_needsConsent() {
        assertEquals(NetworkPolicyDecision.NEEDS_CONSENT, policy.decideHeavyStart(NetworkClass.METERED, false));
    }

    @Test
    public void metered_withConsent_allows() {
        assertEquals(NetworkPolicyDecision.ALLOW, policy.decideHeavyStart(NetworkClass.METERED, true));
    }

    @Test
    public void noNetwork_blocks_regardlessOfConsent() {
        assertEquals(NetworkPolicyDecision.BLOCKED_NO_NETWORK, policy.decideHeavyStart(NetworkClass.NONE, false));
        assertEquals(NetworkPolicyDecision.BLOCKED_NO_NETWORK, policy.decideHeavyStart(NetworkClass.NONE, true));
    }
}
