package org.appdevforall.k2go.contentexchange.domain;

/**
 * The outcome of deleting one item: success with the bytes the box reclaimed, or failure with a
 * reason. Immutable value object. Pure domain type: the Data layer maps the core JSON
 * {@code {ok, freedBytes} | {ok:false, reason}} onto it, so the domain never learns the JSON shape.
 */
public final class DeleteResult {

    private final boolean ok;
    private final long freedBytes;
    private final String reason;

    private DeleteResult(boolean ok, long freedBytes, String reason) {
        this.ok = ok;
        this.freedBytes = freedBytes;
        this.reason = reason;
    }

    public static DeleteResult ok(long freedBytes) {
        return new DeleteResult(true, Math.max(0L, freedBytes), "");
    }

    public static DeleteResult failure(String reason) {
        return new DeleteResult(false, 0L, reason != null ? reason : "");
    }

    public boolean ok() {
        return ok;
    }

    /** Bytes reclaimed by this delete (0 when it failed, or when the box reported no figure). */
    public long freedBytes() {
        return freedBytes;
    }

    public String reason() {
        return reason;
    }
}
