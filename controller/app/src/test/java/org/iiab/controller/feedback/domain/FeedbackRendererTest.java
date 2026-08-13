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

    @Test public void shareTextCarriesRecipientSubjectAndBody() {
        String text = new FeedbackRenderer().shareText(payload());
        // Recipient stand-in first (a messaging share has no "to" field), then subject, then body.
        assertTrue("has To line", text.startsWith("To: " + FeedbackRenderer.RECIPIENT + "\n"));
        assertTrue("has subject", text.contains("[K2Go]"));
        assertTrue("has diagnostics", text.contains("App version: 1.2.3 (build 45)"));
        assertTrue("has device", text.contains("Device: Acme Phone X"));
        assertTrue("has the user message", text.contains("It broke when I tapped share"));
    }

    @Test public void shareTextMatchesTheEmailBody() {
        FeedbackPayload p = payload();
        FeedbackRenderer r = new FeedbackRenderer();
        // The messaging text ends with exactly the email body, so both channels carry the same report.
        assertTrue(r.shareText(p).endsWith(r.render(p).body()));
    }

    @Test public void emailBodyUnchanged() {
        // Guard the existing render() contract while we add the share path.
        String body = new FeedbackRenderer().render(payload()).body();
        assertTrue(body.startsWith("Product: K2Go\n"));
        assertTrue(body.contains("\n--- Your message ---\n"));
    }

    @Test public void recipientIsTheSharedInbox() {
        assertEquals("feedback+k2go@appdevforall.org", FeedbackRenderer.RECIPIENT);
    }
}
