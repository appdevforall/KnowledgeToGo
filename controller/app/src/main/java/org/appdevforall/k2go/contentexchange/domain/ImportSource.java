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
     * Ingest one item whose archive already sits at {@code payloadBoxPath}: a box-visible path inside
     * the core's import area. The core delegates to the app's own import. Throws when the core rejects
     * the item (bad path, no space, adapter failure).
     */
    void importItem(String app, String entryJson, String payloadBoxPath) throws Exception;

    /**
     * Delete one item from the box, delegating to the app's own delete. Available for the replace-older
     * flow and the manager's reclaim action; the import sequence does not call it on its own.
     */
    void delete(ItemRef ref) throws Exception;
}
