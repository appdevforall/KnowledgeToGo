package org.appdevforall.k2go.analytics;

import android.content.Context;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;

/**
 * Standard-flavor bridge to the Firebase Analytics SDK (K2GO-402). This is the ONLY class that
 * references the non-free Firebase library, and it exists only in the "standard" source set. The
 * "fdroid" flavor provides a no-op twin and declares no Firebase dependency, so that build never
 * links the AAR. Callers stay in {@link AnalyticsClient}.
 *
 * <p>Every method is already gated by {@code BuildConfig.ANALYTICS_ENABLED} in AnalyticsClient, so
 * these run only in a standard build that has google-services.json.
 */
final class FirebaseAnalyticsBridge {

    private FirebaseAnalyticsBridge() {
    }

    static void setCollectionEnabled(Context app, boolean enabled) {
        FirebaseAnalytics.getInstance(app).setAnalyticsCollectionEnabled(enabled);
    }

    static void setUserProperty(Context app, String key, String value) {
        FirebaseAnalytics.getInstance(app).setUserProperty(key, value);
    }

    static void logEvent(Context app, String name, Bundle params) {
        FirebaseAnalytics.getInstance(app).logEvent(name, params);
    }
}
