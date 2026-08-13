package org.iiab.controller.feedback.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedbackRendererTest {

    private static FeedbackPayload payload() {
        return FeedbackPayload.builder(FeedbackType.GENERAL)
                .appVersion("1.2.3")
                .appBuild(45)
                .androidRelease("14")
                .device("Acme Phone X")
                .abi("arm64-v8a")
                .message("It broke when I tapped share")
                .build();
    }

    @Test public void bodyCarriesTheDiagnosticsAndMessage() {
        // ADFA-5130: the hybrid share and the clipboard fallback both send this body verbatim,
        // so it must hold the whole report as real, extractable text.
        String body = new FeedbackRenderer().render(payload()).body();
        assertTrue(body.startsWith("Product: K2Go\n"));
        assertTrue(body.contains("App version: 1.2.3 (build 45)"));
        assertTrue(body.contains("Android: 14"));
        assertTrue(body.contains("Device: Acme Phone X"));
        assertTrue(body.contains("ABI: arm64-v8a"));
        assertTrue(body.contains("\n--- Your message ---\nIt broke when I tapped share\n"));
    }

    @Test public void subjectIsProductTagged() {
        assertTrue(new FeedbackRenderer().render(payload()).subject().startsWith("[K2Go]"));
    }

    @Test public void recipientIsTheSharedInbox() {
        assertEquals("feedback+k2go@appdevforall.org", FeedbackRenderer.RECIPIENT);
    }
}
