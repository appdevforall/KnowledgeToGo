package org.appdevforall.k2go.contentexchange.domain;

import java.util.List;

/**
 * Domain port for the BOX side of an import: validate a bundle against this box, ingest one already
 * staged item, and (for the manager's reclaim path) delete one item. The Data layer implements it
 * against the core REST API through dash-node's {@code /v1/*} relay; the domain never learns the
 * transport or the JSON shape.
 *
 * <p>All methods must be called off the main thread.
 */
public interface ImportSource {

    /**
     * Validate the manifest items against this box: one verdict per entry, in the same order. Nothing
     * is moved. An unknown app yields a {@code NO_APP} verdict (partial import is first-class).
     */
    List<ImportVerdict> validate(List<ImportEntry> entries) throws Exception;

    /**
     * Start the import long operation for one item whose archive already sits at
     * {@code payloadBoxPath}: a box-visible path inside the core's import area. Returns the opId to
     * poll (K2GO-376). A Kolibri import takes 35 to 141 s, so it cannot be one blocking request; the
     * caller starts it here and polls {@link #importStatus}. Throws when the core rejects the start
     * (bad path, unknown app).
     */
    String startImport(String app, String entryJson, String payloadBoxPath) throws Exception;

    /**
     * Poll one import operation's status. {@link ImportStatus.State#GONE} means the op was swept, so
     * the caller re-starts once. Throws on a transport failure (the caller tolerates a few blips).
     */
    ImportStatus importStatus(String opId) throws Exception;

    /** Abort one import operation (best-effort): the box stops the ingest and drops the op. */
    void abortImport(String opId);

    /**
     * Delete one item from the box, delegating to the app's own delete. Available for the replace-older
     * flow and the manager's reclaim action; the import sequence does not call it on its own.
     */
    void delete(ItemRef ref) throws Exception;
}
