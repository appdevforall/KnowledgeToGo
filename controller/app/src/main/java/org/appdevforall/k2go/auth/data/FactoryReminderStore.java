/*
 * ============================================================================
 * Name        : FactoryReminderStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. The user's choice to stop the "factory password in use" reminder, with the
 *               time of that choice as the record that the risk was shown and accepted. Set by the
 *               confirmation dialog; cleared by the switch in Settings > Authentication.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class FactoryReminderStore {

    private static final String PREFS = "k2go_auth";
    private static final String KEY_OFF_AT = "factory_reminders_off_at";

    private final SharedPreferences prefs;

    public FactoryReminderStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isOff() {
        return prefs.getLong(KEY_OFF_AT, 0L) > 0L;
    }

    public void turnOff(long nowMillis) {
        prefs.edit().putLong(KEY_OFF_AT, nowMillis).apply();
    }

    public void turnOn() {
        prefs.edit().remove(KEY_OFF_AT).apply();
    }
}
