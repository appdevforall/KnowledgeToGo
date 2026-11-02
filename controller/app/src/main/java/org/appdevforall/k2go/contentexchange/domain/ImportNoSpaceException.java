package org.appdevforall.k2go.contentexchange.domain;

/**
 * The content volume ran out of space during an import ingest. A typed signal so the import service
 * stops the run with a space message, instead of matching English prose in a failure string. The
 * core reports the shortfall as HTTP 507; the data-layer import source maps that to this exception.
 * Any other failure stays a per-item retry.
 */
public final class ImportNoSpaceException extends Exception {

    public ImportNoSpaceException(String message) {
        super(message);
    }
}
