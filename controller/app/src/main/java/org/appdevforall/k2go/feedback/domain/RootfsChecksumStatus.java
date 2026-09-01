package org.appdevforall.k2go.feedback.domain;

/**
 * Integrity state of the installed rootfs at install time.
 *
 * <p>Pure domain type. {@link #label()} is the stable English wording used in the
 * email body (and later JSON): whether the rootfs carried a checksum and how
 * verification went.
 */
public enum RootfsChecksumStatus {
    /** A checksum was present and verification passed. */
    VERIFIED("sha256 verified"),
    /** A checksum was present but did not match (corrupt/altered download). */
    MISMATCH("sha256 mismatch"),
    /** The rootfs carried no checksum, so it was installed unverifiable. */
    ABSENT("none provided"),
    /** A checksum was present but verification was skipped (e.g. a restore path). */
    UNVERIFIED("not verified");

    private final String label;

    RootfsChecksumStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
