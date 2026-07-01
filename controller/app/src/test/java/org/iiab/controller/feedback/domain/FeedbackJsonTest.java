package org.iiab.controller.feedback.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** JVM unit test (runs in CI) for the Worker JSON body of a feedback report. */
public class FeedbackJsonTest {

    @Test
    public void rendersWorkerFields_withWireType_andProduct() {
        FeedbackPayload p = FeedbackPayload.builder(FeedbackType.CONTENT_REQUEST)
                .message("please add offline maps")
                .appVersion("v0.4.0-beta")
                .appBuild(55)
                .androidRelease("14")
                .device("Google Pixel 7")
                .abi("arm64-v8a")
                .build();

        JSONObject o = new FeedbackJson().render(p);

        assertEquals("K2Go", o.optString("product"));
        assertEquals("content_request", o.optString("type"));
        assertEquals("please add offline maps", o.optString("message"));
        assertEquals(55, o.optInt("app_build"));
        assertEquals("v0.4.0-beta", o.optString("app_version"));
        assertEquals("arm64-v8a", o.optString("abi"));
    }

    @Test
    public void omitsEmptyOptionalFields() {
        FeedbackPayload p = FeedbackPayload.builder(FeedbackType.BUG)
                .message("x")
                .appBuild(1)
                .build();

        JSONObject o = new FeedbackJson().render(p);

        assertFalse("no contact when absent", o.has("contact"));
        assertFalse("no stack_trace when absent", o.has("stack_trace"));
        assertTrue(o.has("product"));
        assertEquals("bug", o.optString("type"));
    }

    @Test
    public void feedbackTypeWireTokensAreStableSnakeCase() {
        assertEquals("crash", FeedbackType.CRASH.wire());
        assertEquals("bug", FeedbackType.BUG.wire());
        assertEquals("suggestion", FeedbackType.SUGGESTION.wire());
        assertEquals("content_request", FeedbackType.CONTENT_REQUEST.wire());
    }
}
