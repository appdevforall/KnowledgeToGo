package org.appdevforall.k2go.contentexchange.domain;

/**
 * The box refused to start an rsync pull (K2GO-577): its core is older than the pull, or it has no
 * rsync. A typed signal so the import service moves the item over the HTTP fetch instead of failing
 * it. The data-layer import source maps the core's 400 / 501 on a pull start to this exception.
 */
public final class PullUnsupportedException extends Exception {
    public PullUnsupportedException(String message) {
        super(message);
    }
}
