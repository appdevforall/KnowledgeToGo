package org.iiab.controller.analytics.domain;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single operational analytics event: a name plus flat key/value fields. Transport-
 * agnostic and JVM-testable; the {@link org.iiab.controller.analytics.AnalyticsClient}
 * hands its JSON to the delivery backbone. Never carries PII — only anonymous,
 * operational fields (install id, app/device build, timing).
 */
public final class AnalyticsEvent {

    private final JSONObject payload = new JSONObject();

    private AnalyticsEvent(String name) {
        safePut("event", name);
    }

    public static AnalyticsEvent named(String name) {
        return new AnalyticsEvent(name);
    }

    /** Adds a field; null values are skipped so the payload stays clean. */
    public AnalyticsEvent with(String key, Object value) {
        if (value != null) {
            safePut(key, value);
        }
        return this;
    }

    private void safePut(String key, Object value) {
        try {
            payload.put(key, value);
        } catch (JSONException ignored) {
            // org.json only throws on NaN/Infinity or a null key; not possible here.
        }
    }

    public JSONObject toJsonObject() {
        return payload;
    }

    public String toJson() {
        return payload.toString();
    }
}
