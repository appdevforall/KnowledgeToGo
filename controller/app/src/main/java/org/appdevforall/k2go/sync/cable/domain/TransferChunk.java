/*
 * ============================================================================
 * Name        : TransferChunk.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : A group of files that one rsync run copies with --files-from,
 *               and the planner that cuts the dry-run file list into chunks of
 *               similar size (K2GO-576). Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TransferChunk {

    /** One file from the dry-run list: path relative to the module root, size in bytes. */
    public static final class FileEntry {
        public final String path;
        public final long size;

        public FileEntry(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    public final int id;
    public final List<String> paths;
    public final long bytes;

    TransferChunk(int id, List<String> paths, long bytes) {
        this.id = id;
        this.paths = Collections.unmodifiableList(paths);
        this.bytes = bytes;
    }

    /**
     * Cuts {@code files} into chunks of about {@code targetBytes} and at most {@code maxFiles}.
     * A file larger than the target is a chunk of its own; those go first, largest first, so a
     * big file does not start last on the slower path. The small files keep their list order.
     */
    public static List<TransferChunk> plan(List<FileEntry> files, long targetBytes, int maxFiles) {
        List<FileEntry> big = new ArrayList<>();
        List<FileEntry> small = new ArrayList<>();
        for (FileEntry f : files) (f.size >= targetBytes ? big : small).add(f);
        big.sort((a, b) -> Long.compare(b.size, a.size));

        List<TransferChunk> chunks = new ArrayList<>();
        for (FileEntry f : big) {
            chunks.add(new TransferChunk(chunks.size(), Collections.singletonList(f.path), f.size));
        }
        List<String> paths = new ArrayList<>();
        long bytes = 0;
        for (FileEntry f : small) {
            paths.add(f.path);
            bytes += f.size;
            if (bytes >= targetBytes || paths.size() >= maxFiles) {
                chunks.add(new TransferChunk(chunks.size(), paths, bytes));
                paths = new ArrayList<>();
                bytes = 0;
            }
        }
        if (!paths.isEmpty()) chunks.add(new TransferChunk(chunks.size(), paths, bytes));
        return chunks;
    }
}
