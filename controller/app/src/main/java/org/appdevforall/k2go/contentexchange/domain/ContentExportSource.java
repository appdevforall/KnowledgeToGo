package org.appdevforall.k2go.contentexchange.domain;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Domain port for the BOX side of an export: plan the items, stream one item's archive, and
 * build the collection manifest. The Data layer implements it against the core REST API
 * (through dash-node's {@code /v1/*} relay); the domain never learns the transport or the
 * JSON shape.
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

    /** Open the streaming archive for one item. The caller copies the stream, then closes it. */
    ItemPayload open(ItemRef ref) throws Exception;

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
