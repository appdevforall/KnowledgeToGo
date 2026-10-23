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
