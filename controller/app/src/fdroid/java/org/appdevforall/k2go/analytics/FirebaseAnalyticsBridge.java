package org.appdevforall.k2go.analytics;

import android.content.Context;
import android.os.Bundle;

/**
 * Fdroid-flavor no-op twin of the Firebase bridge (K2GO-402). The fdroid build declares no Firebase
 * dependency, so this variant links no non-free library. It is never called at runtime: AnalyticsClient
 * gates every path on {@code BuildConfig.ANALYTICS_ENABLED}, which is false in the fdroid flavor. This
 * class exists only so AnalyticsClient (in the shared source set) still compiles.
 */
final class FirebaseAnalyticsBridge {

    private FirebaseAnalyticsBridge() {
    }

    static void setCollectionEnabled(Context app, boolean enabled) {
    }

    static void setUserProperty(Context app, String key, String value) {
    }

    static void logEvent(Context app, String name, Bundle params) {
    }
}
