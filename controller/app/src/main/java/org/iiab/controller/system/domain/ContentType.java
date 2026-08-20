/*
 * ============================================================================
 * Name        : ContentType.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The kinds of content a run can carry, and how each one runs.
 *               Pure JVM, no Android (ADFA-4954 / ADR-5061).
 * ============================================================================
 */
package org.iiab.controller.system.domain;

/**
 * Every kind of content the app can be asked to add, with its execution class.
 *
 * <p><b>Why this is a type and not a list of ifs.</b> Four content types arrived one
 * at a time, and each arrival had to be registered by hand in every screen that asks
 * "is there anything to install?". Nobody found them all: a survey for ADFA-4954
 * turned up five places enumerating the types with three different subsets, and the
 * omissions were real bugs — a courses-only wizard run that never downloaded
 * anything, a run that declared itself finished while courses were still
 * downloading, a stale map selection surviving into the next run.
 *
 * <p>So the list lives once, here, in the domain: adding a fifth type is one line in
 * this enum and every caller follows.
 *
 * <p><b>Maps is the one that is not like the others</b>, and the difference is the
 * {@link Operation.ExecutionClass}, not a special case. ZIM, Books and Courses are
 * {@code LIVE}: the device POSTs to the in-server REST core and the box stays up.
 * Maps is {@code STOPPED}: a {@code runrole} under proot, whose progress belongs to
 * the module queue rather than to a download stream. Callers that ask "is any
 * content waiting?" must count Maps; callers that ask "is any download stream in
 * play?" must not. Both questions are legitimate and they have different answers, so
 * the class is carried rather than assumed.
 *
 * <p>Note that the class here describes the <em>content</em> operation. Installing a
 * platform's <em>app</em> is always {@code STOPPED} whatever its content does — the
 * Courses app is a proot module while its channels are REST — which is exactly the
 * distinction {@link Operation} exists to keep.
 */
public enum ContentType {

    /** Wikipedia and other ZIM collections, downloaded by the server. */
    ZIM("zim", "kiwix", Operation.ExecutionClass.LIVE),

    /** Gutenberg books, downloaded by the server. */
    BOOKS("books", "books", Operation.ExecutionClass.LIVE),

    /** Kolibri channels, imported by the server. Tens of GB at the top end. */
    COURSES("kolibri", "kolibri", Operation.ExecutionClass.LIVE),

    /** Map layers, built by an Ansible runrole under proot. */
    MAPS("maps", "maps", Operation.ExecutionClass.STOPPED);

    private final String key;
    private final String endpoint;
    private final Operation.ExecutionClass executionClass;

    ContentType(String key, String endpoint, Operation.ExecutionClass executionClass) {
        this.key = key;
        this.endpoint = endpoint;
        this.executionClass = executionClass;
    }

    /**
     * The stable identifier used for this type across the app — the progress rows,
     * the detail-card hints and the platform name on an {@link Operation}.
     */
    public String key() {
        return key;
    }

    /**
     * The identifier the Home surface uses for this type's platform, which differs from
     * {@link #key()} for exactly one type: the Wikipedia card is {@code kiwix} while its
     * content key is {@code zim}. Carrying the alias here retires the hand-rolled
     * {@code "kiwix" -> "zim"} remap the callers used to keep (ADFA-5062).
     */
    public String endpoint() {
        return endpoint;
    }

    public Operation.ExecutionClass executionClass() {
        return executionClass;
    }

    /** Runs over REST with the box up, as opposed to under proot with it stopped. */
    public boolean isLive() {
        return executionClass == Operation.ExecutionClass.LIVE;
    }

    /** The content operation for this type, for {@link OperationDispatcher}. */
    public Operation operation() {
        return Operation.of(key, Operation.Kind.CONTENT, executionClass);
    }

    /**
     * @param key a row/hint key
     * @return the matching type, or {@code null} — an unknown key is not an error
     *         here, and callers that exclude "the one I just started" rely on an
     *         unrecognised name excluding nothing
     */
    public static ContentType byKey(String key) {
        if (key == null) {
            return null;
        }
        for (ContentType t : values()) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }

    /**
     * @param endpoint a Home-surface platform endpoint (e.g. {@code kiwix})
     * @return the matching type, or {@code null} for an unknown endpoint
     */
    public static ContentType byEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        for (ContentType t : values()) {
            if (t.endpoint.equals(endpoint)) {
                return t;
            }
        }
        return null;
    }
}
