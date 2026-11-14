package org.appdevforall.k2go.contentexchange.domain;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Domain port for the SOURCE of an import: where the bundle is read from. The mirror of
 * {@link ExportCarrier} (the write side). One implementation is the SAF-backed drive reader; a future
 * network carrier reuses the same port, so it is never hard-wired into the import sequence.
 *
 * <p>Only {@code java.io} crosses this boundary: no Android, no SAF type. The manifest is a few KB, so
 * it is returned whole; payloads stream.
 */
public interface ImportCarrier {

    /** A short, human name of the chosen source (e.g. the folder name), for the UI. */
    String sourceLabel();

    /** The top-level {@code manifest.json} bytes, or {@code null} when the bundle has none. */
    byte[] readManifest() throws IOException;

    /** The files directly in the chosen folder (name + size), for the no-manifest scan and lookups. */
    List<DriveFile> listFiles() throws IOException;

    /** Open a readable stream for one payload file; the caller copies it, then closes it. */
    InputStream openPayload(String name) throws IOException;

    /**
     * Open one payload from byte {@code offset} to resume a cut copy (K2GO-577). The source may send
     * the whole item instead; {@link Payload#start()} says where the body starts. Default: the whole
     * item, not resumable (a drive source has nothing to resume).
     */
    default Payload openPayloadFrom(String name, long offset) throws IOException {
        return new Payload(openPayload(name), 0L, false);
    }

    /**
     * A payload body, the item byte it starts at, and whether the item is one exact file the source
     * can resume (so a cut copy is worth keeping). Immutable value.
     */
    final class Payload {

        private final InputStream stream;
        private final long start;
        private final boolean exact;

        public Payload(InputStream stream, long start, boolean exact) {
            this.stream = stream;
            this.start = start;
            this.exact = exact;
        }

        public boolean exact() {
            return exact;
        }

        public InputStream stream() {
            return stream;
        }

        public long start() {
            return start;
        }
    }

    /**
     * Signal that the run finished with every requested item delivered (a clean success). The default
     * is a no-op (a drive source has nothing to close); the network carrier uses it to tell the sender
     * gate the receiver has everything, so the share session is released. Called at most once, only on
     * a fully-complete run.
     */
    default void onAllDelivered() throws IOException {
    }

    /** One file in the source folder: its display name and byte size. Immutable value. */
    final class DriveFile {

        private final String name;
        private final long size;

        public DriveFile(String name, long size) {
            this.name = name;
            this.size = size;
        }

        public String name() {
            return name;
        }

        public long size() {
            return size;
        }
    }
}
