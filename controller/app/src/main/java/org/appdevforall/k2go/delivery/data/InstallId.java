package org.appdevforall.k2go.delivery.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** Anonymous, stable per-install id (a random UUID). No PII, no device identifiers. */
public final class InstallId {

    private static final String PREFS = "iiab_delivery";
    private static final String KEY = "install_id";

    private InstallId() {
    }

    public static synchronized String get(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(KEY, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString(KEY, id).apply();
        }
        return id;
    }
}
