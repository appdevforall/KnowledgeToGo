package org.iiab.controller.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.security.NetworkSecurityPolicy;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device proof of the cleartext scoping (tech-debt S18, ADFA-4714).
 *
 * <p>Runs in the app-under-test process, so {@link NetworkSecurityPolicy} reflects the app's
 * network-security-config. Asserts the exact contract: cleartext http is permitted ONLY on
 * loopback (the only http hosts the app/WebView reach — portal :8085, tier-3 help :8114) and
 * denied everywhere else. The denied cases also prove the config is actually active, not a
 * no-op. If a real feature ever needed another cleartext host, the "denied" assert for it
 * would fail here instead of silently breaking at runtime.
 */
@RunWith(AndroidJUnit4.class)
public class NetworkSecurityConfigTest {

    private final NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();

    @Test
    public void cleartextPermittedOnLoopback() {
        assertTrue("localhost must allow cleartext (portal/dashboard/pdf.js/k2go-docs)",
                policy.isCleartextTrafficPermitted("localhost"));
        assertTrue("127.0.0.1 must allow cleartext (tier-3 help tooltips)",
                policy.isCleartextTrafficPermitted("127.0.0.1"));
    }

    @Test
    public void cleartextDeniedOffLoopback() {
        assertFalse("external hosts must not allow cleartext",
                policy.isCleartextTrafficPermitted("example.com"));
        assertFalse("LAN IPs must not allow cleartext",
                policy.isCleartextTrafficPermitted("192.168.1.50"));
        assertFalse("the OTA host is https-only; cleartext to it must be denied",
                policy.isCleartextTrafficPermitted("iiab.switnet.org"));
    }
}
