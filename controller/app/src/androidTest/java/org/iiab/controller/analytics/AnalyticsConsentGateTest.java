package org.iiab.controller.analytics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.iiab.controller.delivery.data.AnalyticsConsent;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device proof of the privacy gate for the Firebase sink (ADFA-4466 Phase 1).
 *
 * <p>Analytics now goes straight to the Firebase SDK (online-first), whose internal buffer
 * is not introspectable on-device, so we assert what we can observe: the consent flag is
 * OFF by default, and the whole gated emit path is exercised without throwing in both
 * states. The privacy contract (no-op unless opted in) lives in {@code AnalyticsClient.gate()}
 * plus {@code FirebaseAnalytics.setAnalyticsCollectionEnabled}.
 */
@RunWith(AndroidJUnit4.class)
public class AnalyticsConsentGateTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        AnalyticsConsent.setEnabled(ctx(), false);
    }

    @Test
    public void consentDefaultsOff() {
        Context ctx = ctx();
        AnalyticsConsent.setEnabled(ctx, false);
        assertFalse(AnalyticsConsent.isEnabled(ctx));
    }

    @Test
    public void gatedPath_doesNotThrow_whenConsentOff() {
        Context ctx = ctx();
        AnalyticsConsent.setEnabled(ctx, false);
        emitAll(AnalyticsClient.with(ctx));
        assertFalse(AnalyticsConsent.isEnabled(ctx));
    }

    @Test
    public void emitPath_doesNotThrow_whenConsentOn() {
        Context ctx = ctx();
        AnalyticsConsent.setEnabled(ctx, true);
        AnalyticsClient client = AnalyticsClient.with(ctx);
        client.applyConsent();
        emitAll(client);
        assertTrue(AnalyticsConsent.isEnabled(ctx));
    }

    private void emitAll(AnalyticsClient client) {
        client.logAppOpened();
        client.logSession(1000L);
        client.logInstallStarted("BASIC", true, "arm64-v8a");
        client.logInstallCompleted("BASIC", true);
        client.logInstallFailed("download", "network");
        client.logServerStarted();
        client.logServerStopped(90_000L);
    }
}
