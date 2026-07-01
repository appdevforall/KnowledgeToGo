package org.iiab.controller.analytics.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** JVM unit test (runs in CI) for the operational analytics event payload. */
public class AnalyticsEventTest {

    @Test
    public void carriesNameAndFields() {
        JSONObject o = AnalyticsEvent.named("app_opened")
                .with("install_id", "abc")
                .with("app_build", 55)
                .with("session_ms", 1234L)
                .toJsonObject();

        assertEquals("app_opened", o.optString("event"));
        assertEquals("abc", o.optString("install_id"));
        assertEquals(55, o.optInt("app_build"));
        assertEquals(1234L, o.optLong("session_ms"));
    }

    @Test
    public void skipsNullValues() {
        JSONObject o = AnalyticsEvent.named("first_run")
                .with("binaries_tag", null)
                .with("device", "Pixel 7")
                .toJsonObject();

        assertFalse("null field must not be written", o.has("binaries_tag"));
        assertTrue(o.has("device"));
    }

    @Test
    public void toJsonIsParseableObject() throws Exception {
        String json = AnalyticsEvent.named("session").with("session_ms", 10L).toJson();
        JSONObject reparsed = new JSONObject(json);
        assertEquals("session", reparsed.getString("event"));
        assertEquals(10L, reparsed.getLong("session_ms"));
    }
}
