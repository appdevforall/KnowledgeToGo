package org.appdevforall.k2go.contentexchange.domain;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Domain port for the DESTINATION of an export: where the bytes land. One implementation is the
 * SAF-backed drive writer; the import flow and a future network carrier reuse the same port, so
 * the carrier is never hard-wired into the export sequence.
 *
 * <p>Only {@code java.io} crosses this boundary: no Android, no SAF type. The carrier chooses the
 * final name for a payload (a destination may rename on a collision), so {@link #openPayload}
 * reports the name actually used, which the manifest then records.
 */
public interface ExportCarrier {

    /** A short, human name of the chosen destination (e.g. the folder name), for the UI. */
    String destinationLabel();

    /** Open a writable stream for one payload file; reports the name the destination stored. */
    PayloadSink openPayload(String desiredName) throws IOException;

    /** Open a writable stream for the top-level {@code manifest.json}. */
    OutputStream openManifest() throws IOException;

    /** An open payload stream plus the name the destination actually stored it under. */
    final class PayloadSink {

        private final OutputStream stream;
        private final String name;

        public PayloadSink(OutputStream stream, String name) {
            this.stream = stream;
            this.name = name;
        }

        public OutputStream stream() {
            return stream;
        }

        public String name() {
            return name;
        }
    }
}
