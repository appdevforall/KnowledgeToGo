package org.iiab.controller.delivery.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Endpoint + shared token for the Cloudflare Worker, stored in prefs so they can be set
 * at runtime once the Worker is deployed (infra) — no rebuild needed. Blank by default;
 * while blank the backbone keeps queuing but does not send, so nothing is lost.
 *
 * <p>The runtime setters are also what the debug adb receiver and the instrumented test
 * use to point the backbone at a throwaway endpoint (webhook.site / MockWebServer).
 */
public final class DeliveryConfig {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY_ENDPOINT = "endpoint_url";
    private static final String KEY_TOKEN = "shared_token";

    /** Build-time defaults. Blank until the Cloudflare Worker exists. */
    public static final String DEFAULT_ENDPOINT = "";
    public static final String DEFAULT_TOKEN = "";

    private DeliveryConfig() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getEndpoint(Context ctx) {
        return prefs(ctx).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT);
    }

    public static String getToken(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, DEFAULT_TOKEN);
    }

    /** Runtime override (Worker URL once known, or a debug/test endpoint). */
    public static void setEndpoint(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_ENDPOINT, url == null ? "" : url).apply();
    }

    public static void setToken(Context ctx, String token) {
        prefs(ctx).edit().putString(KEY_TOKEN, token == null ? "" : token).apply();
    }

    public static boolean isConfigured(Context ctx) {
        String url = getEndpoint(ctx);
        return url != null && !url.isEmpty();
    }
}
