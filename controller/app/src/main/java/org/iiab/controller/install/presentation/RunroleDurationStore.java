package org.iiab.controller.install.presentation;

import android.content.Context;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * ADFA-5228: per-device, per-role learned task durations for the ETA. Stored as one JSON object per
 * role in SharedPreferences ({@code {taskName: ms}}). Blended with a simple moving average so a
 * one-off spike doesn't dominate and the estimate converges over installs. Device-specific by
 * design — build-log times aren't portable across hardware.
 */
public final class RunroleDurationStore {

    private static final String PREFS = "runrole_timings";
    private static final double ALPHA = 0.5;   // weight of the newest measurement vs the stored one

    private RunroleDurationStore() {}

    /** Learned durations (ms) for a role, keyed by task name. Empty when nothing learned yet. */
    public static Map<String, Long> load(Context ctx, String role) {
        Map<String, Long> out = new HashMap<>();
        if (ctx == null || role == null || role.isEmpty()) return out;
        String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(role, null);
        if (json == null) return out;
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.getLong(k));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Blend this run's measured durations into the stored average and persist. */
    public static void blendAndSave(Context ctx, String role, Map<String, Long> measured) {
        if (ctx == null || role == null || role.isEmpty() || measured == null || measured.isEmpty()) return;
        Map<String, Long> merged = load(ctx, role);
        for (Map.Entry<String, Long> e : measured.entrySet()) {
            long m = Math.max(0L, e.getValue());
            Long old = merged.get(e.getKey());
            merged.put(e.getKey(), old == null ? m : Math.round(ALPHA * m + (1 - ALPHA) * old));
        }
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Long> e : merged.entrySet()) o.put(e.getKey(), (long) e.getValue());
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(role, o.toString()).apply();
        } catch (Exception ignored) { }
    }
}
