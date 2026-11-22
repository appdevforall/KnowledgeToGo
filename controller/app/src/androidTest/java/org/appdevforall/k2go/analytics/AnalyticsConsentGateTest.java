package org.appdevforall.k2go.analytics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.appdevforall.k2go.delivery.data.OnlineConsent;
import org.junit.After;
import org.junit.Before;
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

    private boolean before;

    @Before
    public void setUp() {
        before = OnlineConsent.isEnabled(ctx());
    }

    @After
    public void tearDown() {
        OnlineConsent.setEnabled(ctx(), before);
    }

    @Test
    public void consentDefaultsOff() {
        Context ctx = ctx();
        ctx.getSharedPreferences("iiab_delivery", Context.MODE_PRIVATE).edit().remove("online_choice").commit();
        assertFalse(OnlineConsent.isEnabled(ctx));
    }

    @Test
    public void gatedPath_doesNotThrow_whenConsentOff() {
        Context ctx = ctx();
        OnlineConsent.setEnabled(ctx, false);
        emitAll(AnalyticsClient.with(ctx));
        assertFalse(OnlineConsent.isEnabled(ctx));
    }

    @Test
    public void emitPath_doesNotThrow_whenConsentOn() {
        Context ctx = ctx();
        OnlineConsent.setEnabled(ctx, true);
        AnalyticsClient client = AnalyticsClient.with(ctx);
        client.applyConsent();
        emitAll(client);
        assertTrue(OnlineConsent.isEnabled(ctx));
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
