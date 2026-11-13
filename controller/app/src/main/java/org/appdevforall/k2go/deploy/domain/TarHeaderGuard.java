/*
 * ============================================================================
 * Name        : TarHeaderGuard.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-570. Path-traversal check inside the extract stream. Reads the tar
 *               headers of the uncompressed stream as the feeder passes it to tar, and
 *               stops before the header of a member that escapes the root. This replaces
 *               the separate listing pass when the archive integrity is already confirmed.
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Streaming tar header walker (ustar, GNU long names and old sparse, pax "path"). Pure JVM, not thread-safe. */
public final class TarHeaderGuard {

    private static final int BLOCK = 512;
    /** Longest GNU long-name or pax header we read; a larger one is refused. */
    private static final int MAX_META = 64 * 1024;

    private final byte[] header = new byte[BLOCK];
    private int headerFill;
    /** Bytes still to pass before the next header (member data plus padding). */
    private long skip;
    /** Data bytes of a GNU long name or pax header still to capture. */
    private long metaLeft;
    private byte metaType;
    private ByteArrayOutputStream meta;
    /** Name for the next member, from a GNU long name or a pax "path". */
    private String pendingName;
    private String rejected;
    /** Old GNU sparse: extension blocks follow the header, then {@link #sparseData} bytes of data. */
    private boolean sparseExt;
    private long sparseData;

    /**
     * Passes {@code len} bytes of the uncompressed stream through the guard.
     *
     * @return how many leading bytes are safe to give to tar: {@code len}, or the offset of the
     *         header of the first member that escapes the root (then {@link #rejected()} names it).
     */
    public int accept(byte[] b, int off, int len) {
        if (rejected != null) return 0;
        final int end = off + len;
        int pos = off;
        while (pos < end) {
            if (skip > 0) {
                int n = (int) Math.min(skip, end - pos);
                if (metaLeft > 0) {
                    int m = (int) Math.min(metaLeft, n);
                    meta.write(b, pos, m);
                    metaLeft -= m;
                    if (metaLeft == 0) finishMeta();
                }
                pos += n;
                skip -= n;
                continue;
            }
            final int headerStart = pos - headerFill;
            int n = Math.min(BLOCK - headerFill, end - pos);
            System.arraycopy(b, pos, header, headerFill, n);
            headerFill += n;
            pos += n;
            if (headerFill < BLOCK) break;
            headerFill = 0;
            if (sparseExt) {   // byte 504 of an extension block: more extension blocks follow
                if (header[504] == 0) {
                    sparseExt = false;
                    skip = sparseData;
                }
                continue;
            }
            String bad = onHeader();
            if (bad != null) {
                rejected = bad;
                return Math.max(0, headerStart - off);
            }
        }
        return len;
    }

    /** The member name that escapes the root, or {@code null}. */
    public String rejected() {
        return rejected;
    }

    private String onHeader() {
        if (isZero(header)) return null;   // end-of-archive blocks
        final long size = parseSize();
        if (size < 0) return "(unreadable tar header)";
        final long padded = (size + BLOCK - 1) / BLOCK * BLOCK;
        final byte type = header[156];
        if (type == 'L' || type == 'x') {
            if (size > MAX_META) return "(tar metadata too large)";
            metaType = type;
            meta = new ByteArrayOutputStream((int) size);
            metaLeft = size;
            skip = padded;
            if (size == 0) finishMeta();
            return null;
        }
        if (type == 'g' || type == 'K') {   // global pax header, GNU long link name
            skip = padded;
            return null;
        }
        final String name = pendingName != null ? pendingName : ustarName();
        pendingName = null;
        if (type == 'S' && header[482] != 0) {   // old GNU sparse with extension blocks
            sparseExt = true;
            sparseData = padded;
        } else {
            skip = padded;
        }
        return ArchiveEntry.escapesRoot(name) ? name : null;
    }

    private void finishMeta() {
        byte[] data = meta.toByteArray();
        meta = null;
        if (metaType == 'L') {
            pendingName = cString(data, 0, data.length);
        } else {
            String path = paxPath(data);
            if (path != null) pendingName = path;
        }
    }

    private String ustarName() {
        String name = cString(header, 0, 100);
        // POSIX "ustar\0" only. GNU "ustar  " keeps atime/ctime/sparse data at offset 345, not a prefix.
        boolean ustar = header[257] == 'u' && header[258] == 's' && header[259] == 't'
                && header[260] == 'a' && header[261] == 'r' && header[262] == 0;
        if (ustar) {
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) return prefix + "/" + name;
        }
        return name;
    }

    /** Octal ASCII, or base-256 when the high bit of the first byte is set. -1 when unreadable. */
    private long parseSize() {
        if ((header[124] & 0x80) != 0) {
            long v = 0;
            for (int i = 125; i < 136; i++) {
                if (v > (Long.MAX_VALUE >> 8)) return -1;
                v = (v << 8) | (header[i] & 0xff);
            }
            return v;
        }
        long v = 0;
        boolean digits = false;
        for (int i = 124; i < 136; i++) {
            byte c = header[i];
            if (c == 0 || c == ' ') {
                if (digits) break;
                continue;
            }
            if (c < '0' || c > '7') return -1;
            v = (v << 3) + (c - '0');
            digits = true;
        }
        return v;
    }

    /** The "path" value of a pax extended header, or {@code null}. Records: "LEN key=value\n". */
    static String paxPath(byte[] data) {
        int i = 0;
        String path = null;
        while (i < data.length) {
            int sp = i;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, i, sp - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > data.length) break;
            String rec = new String(data, sp + 1, i + len - sp - 1, StandardCharsets.UTF_8);
            if (rec.endsWith("\n")) rec = rec.substring(0, rec.length() - 1);
            if (rec.startsWith("path=")) path = rec.substring(5);
            i += len;
        }
        return path;
    }

    private static String cString(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }
}
