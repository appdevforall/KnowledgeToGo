/*
 * ============================================================================
 * Name        : ModuleBatch.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. The ordered list of modules in the CURRENT install run, persisted so the
 *               install index can render one row per module. The module queue (ModuleQueueState)
 *               only exposes the current module + how many remain + which failed — not the full
 *               batch — so the index derives each row's state (done / running / queued / failed)
 *               from this durable order plus the queue. Written when ModuleProvisioner hands the
 *               batch to the engine; survives reopening the index from the notification; cleared when
 *               the run finishes (the index reaches Library / Finish).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.SharedPreferences;

public final class ModuleBatch {
    private ModuleBatch() {}

    private static final String PREFS = "k2go_module_batch";
    private static final String KEY = "batch";   // comma-joined yamlBaseKeys, install order

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Record the modules being installed in this run (install order). */
    public static void save(Context ctx, String[] keys) {
        prefs(ctx).edit().putString(KEY, keys == null ? "" : android.text.TextUtils.join(",", keys)).apply();
    }

    /** The batch keys in order; empty array if none. */
    public static String[] keys(Context ctx) {
        String raw = prefs(ctx).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return new String[0];
        return raw.split(",");
    }

    public static boolean has(Context ctx) { return keys(ctx).length > 0; }

    public static void clear(Context ctx) { prefs(ctx).edit().remove(KEY).apply(); }
}
