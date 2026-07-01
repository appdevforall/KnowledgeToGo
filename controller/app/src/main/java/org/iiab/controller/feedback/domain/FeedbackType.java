package org.iiab.controller.feedback.domain;

/**
 * The kind of feedback the operator is sending.
 *
 * <p>Pure domain type: no Android and no networking dependencies. {@link #label()} is
 * the stable, English token used in the email subject/body (and later the JSON
 * payload), so it must stay consistent for inbox filtering and triage.
 */
public enum FeedbackType {
    CRASH("Crash"),
    BUG("Bug"),
    SUGGESTION("Suggestion"),
    CONTENT_REQUEST("Content request");

    private final String label;

    FeedbackType(String label) {
        this.label = label;
    }

    /** Stable English label used in the subject/body (and later JSON). */
    public String label() {
        return label;
    }

    /** Lowercase wire token used in the JSON payload / Worker route (stable). */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
