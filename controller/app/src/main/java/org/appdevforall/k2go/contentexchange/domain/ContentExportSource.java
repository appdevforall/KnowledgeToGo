package org.appdevforall.k2go.contentexchange.domain;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Domain port for the BOX side of an export: plan the items, run the per-item staging long
 * operation (prepare, poll, fetch), and build the collection manifest. The Data layer implements
 * it against the core REST API (through dash-node's {@code /v1/*} relay); the domain never learns
 * the transport or the JSON shape.
 *
 * <p>K2GO-507: a large item (a multi-GB Kolibri channel) is staged on the box before any byte
 * streams, so a single blocking request times out. The export is therefore a long operation: the
 * caller {@link #prepare}s an item, {@link #status}-polls while the box stages it (the poll is the
 * liveness signal, so there is no wall-clock timeout), then {@link #fetch}es the staged stream.
 * {@link #abort} drops an operation the caller gives up on. The box owns the operation state; the
 * caller only mirrors it, so there is no competing "where is this export" on the app side.
 *
 * <p>The manifest is built here rather than in the carrier because the source holds the
 * authoritative per-item entries the plan returned (identity, dedup, integrity); the carrier
 * only supplies where each payload landed. Keeping JSON out of the domain is why
 * {@link #buildManifest} returns ready bytes.
 *
 * <p>All methods must be called off the main thread.
 */
public interface ContentExportSource {

    /** Plan the given items: exact sizes + skip list, moving no payload. */
    ExportPlan plan(List<ItemRef> refs) throws Exception;

    /** Start staging one item on the box: returns the operation id and whether it is already ready. */
    ExportPrep prepare(ItemRef ref) throws Exception;

    /** One status reading of a staging operation. A swept operation reports {@code GONE}. */
    ExportStatus status(String opId) throws Exception;

    /** Open the staged stream for a ready operation. The caller copies the stream, then closes it. */
    ItemPayload fetch(String opId) throws Exception;

    /** Best-effort drop of an operation the caller abandons (cancel or error). Never throws. */
    void abort(String opId);

    /**
     * Build the top-level {@code manifest.json} over the items that were written, in the given
     * order, stamping each entry's {@code payload} with the name the carrier actually stored.
     * Returns the UTF-8 bytes of the collection manifest.
     */
    byte[] buildManifest(List<ItemRef> order, Map<ItemRef, String> payloadNames) throws Exception;

    /**
     * One item's archive as a byte stream plus the server-suggested file name
     * (from {@code Content-Disposition}). {@link #close()} releases the underlying connection.
     */
    final class ItemPayload implements Closeable {

        private final InputStream stream;
        private final String filename;
        private final Closeable connection;

        public ItemPayload(InputStream stream, String filename, Closeable connection) {
            this.stream = stream;
            this.filename = filename;
            this.connection = connection;
        }

        public InputStream stream() {
            return stream;
        }

        /** The server-suggested name (may be empty; the caller derives a safe name from it). */
        public String filename() {
            return filename;
        }

        @Override
        public void close() throws IOException {
            try {
                if (stream != null) {
                    stream.close();
                }
            } finally {
                if (connection != null) {
                    connection.close();
                }
            }
        }
    }
}
