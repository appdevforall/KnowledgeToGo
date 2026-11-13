/*
 * ============================================================================
 * Name        : PrivacyException.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. Why the privacy switches could not be read or set.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

public final class PrivacyException extends Exception {

    public enum Reason {
        /** The box server is older than the switches (it needs its self-update). */
        UNSUPPORTED,
        /** The box server did not answer (it is off or starting). */
        UNREACHABLE,
        /** The box answered with an error. */
        FAILED
    }

    private final Reason reason;

    public PrivacyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
