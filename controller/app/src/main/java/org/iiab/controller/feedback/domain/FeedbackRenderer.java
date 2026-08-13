package org.iiab.controller.feedback.domain;

/**
 * Renders a {@link FeedbackPayload} into an {@link EmailContent} for the email-first
 * (v1) transport. Pure: no Android, no I/O \u2014 fully unit-testable.
 *
 * <p>Designed for a shared inbox ({@code feedback@appdevforall.org}): the recipient
 * uses sub-addressing ({@code feedback+k2go@}), the subject is prefixed {@code [K2Go]},
 * and the body opens with {@code Product: K2Go} \u2014 three independent hooks so K2Go
 * reports never mix with other products in the inbox.
 */
public final class FeedbackRenderer {

    /** Shared inbox via plus-addressing so K2Go is auto-separable from other products. */
    public static final String RECIPIENT = "feedback+k2go@appdevforall.org";

    private static final String SUBJECT_PREFIX = "[K2Go] ";
    /** Em dash (U+2014) used as the subject separator; escaped to stay encoding-safe. */
    private static final String DASH = " \u2014 ";

    public EmailContent render(FeedbackPayload p) {
        return new EmailContent(RECIPIENT, subject(p), body(p), p.screenshot());
    }

    private String subject(FeedbackPayload p) {
        StringBuilder s = new StringBuilder(SUBJECT_PREFIX).append(p.type().label());
        if (p.type() == FeedbackType.CRASH) {
            s.append(DASH).append(p.appVersion()).append(" (").append(p.appBuild()).append(")");
        } else if (notEmpty(p.screen())) {
            s.append(DASH).append(p.screen());
        }
        return s.toString();
    }

    private String body(FeedbackPayload p) {
        StringBuilder b = new StringBuilder();
        b.append("Product: K2Go\n");
        b.append("Type: ").append(p.type().label()).append("\n");
        b.append("App version: ").append(p.appVersion())
                .append(" (build ").append(p.appBuild()).append(")\n");
        b.append("Android: ").append(p.androidRelease()).append("\n");
        b.append("Device: ").append(p.device()).append("\n");
        b.append("ABI: ").append(p.abi()).append("\n");
        appendOptional(b, "Rootfs build", p.rootfsBuild());
        if (p.rootfsChecksum() != null) {
            b.append("Rootfs checksum: ").append(p.rootfsChecksum().label()).append("\n");
        }
        appendOptional(b, "Binaries", p.binariesTag());
        appendOptional(b, "Screen", p.screen());
        appendOptional(b, "Contact", p.contactEmail());

        b.append("\n--- Your message ---\n").append(p.message()).append("\n");

        if (notEmpty(p.stackTrace())) {
            b.append("\n--- Stack trace ---\n").append(p.stackTrace()).append("\n");
        }
        return b.toString();
    }

    private static void appendOptional(StringBuilder b, String label, String value) {
        if (notEmpty(value)) {
            b.append(label).append(": ").append(value).append("\n");
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
