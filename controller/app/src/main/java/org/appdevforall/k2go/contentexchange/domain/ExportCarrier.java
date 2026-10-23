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
 * reports the name actually used (relative to the bundle root), which the manifest then records.
 */
public interface ExportCarrier {

    /** A short, human name of the chosen destination (e.g. the folder name), for the UI. */
    String destinationLabel();

    /**
     * Open a writable stream for one payload file inside the bundle, under a per-app {@code subfolder}
     * (e.g. {@code kiwix}); a null or empty subfolder writes at the bundle root. An existing file of
     * the same name in that folder is overwritten (replaced), never left as a {@code name (1)} copy.
     * Reports the stored name relative to the bundle root ({@code subfolder/name}), for the manifest.
     */
    PayloadSink openPayload(String subfolder, String desiredName) throws IOException;

    /** Open a writable stream for the top-level {@code manifest.json}, overwriting any existing one so
     *  the bundle has exactly one authoritative manifest (never a {@code manifest (1).json} copy). */
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
