package org.appdevforall.k2go.contentexchange.domain;

/**
 * Domain port for the BOX side of a delete: remove one item from the box and report what it
 * reclaimed. The Data layer implements it against the core REST API (through dash-node's
 * {@code /v1/*} relay: {@code DELETE /v1/content/{app}/{localRef}}); the domain never learns the
 * transport or the JSON shape.
 *
 * <p>The box owns the real deletion (it delegates to each app's own delete, never removes files
 * blindly), so there is no client-side "is this deletable" rule to duplicate here: the item address
 * is all this port needs. Must be called off the main thread.
 */
public interface ContentDeleteSource {

    /** Delete one item on the box. Returns the outcome; throws only when the box is unreachable. */
    DeleteResult delete(ItemRef ref) throws Exception;
}
