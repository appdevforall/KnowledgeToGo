/*
 * ============================================================================
 * Name        : RootfsIntegrity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Integrity verifier for imported rootfs/backup tarballs. In ONE
 *               streaming pass it (a) recomputes the iiab-tree-sha256-v1 treehash
 *               over the tar's logical members (excluding the integrity member)
 *               and (b) reads the integrity member declaration, then compares.
 *
 *               Dependency-free on purpose: minSdk is 24 with no core-library
 *               desugaring, and Apache Commons Compress reaches into
 *               java.nio.file on newer versions, so we hand-parse the tar the way
 *               RootfsManifest already does (ustar + GNU long name/link + pax
 *               path/linkpath). Byte-parity with tools/rootfs-builder/iiab_tree_hash.py is the
 *               contract; see RootfsTreeHashTest + the data-layer fixture tests.
 * ============================================================================
 */
package org.iiab.controller.deploy.data;

import android.util.Log;

import org.iiab.controller.deploy.domain.RootfsTreeHash;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Recompute-and-compare the embedded integrity treehash. Soft rollout: ABSENT and
 * DECLARED_NONE are non-blocking; MISMATCH and ERROR map to a CORRUPT result that
 * the caller fail-closes on (see RootfsArchiveValidator).
 */
public final class RootfsIntegrity {

    private static final String TAG = "IIAB-RootfsIntegrity";
    private static final String INTEGRITY_MEMBER = "installed-rootfs/iiab/.iiab-rootfs.integrity.json";
    private static final int MAX_DECL_BYTES = 64 * 1024;

    public enum Status {
        /** No integrity member in the archive. */
        ABSENT,
        /** Integrity member present and explicitly declares algo:"none" (e.g. a device backup). */
        DECLARED_NONE,
        /** Real treehash present and recomputation matches. */
        MATCH,
        /** Real treehash present and recomputation does NOT match -> corrupt/tampered. */
        MISMATCH,
        /** Could not parse/recompute (unhashable member, bad JSON, unknown algo, I/O). */
        ERROR
    }

    public static final class Result {
        public final Status status;
        public final String declaredHash; // nullable
        public final String computedHash; // nullable

        Result(Status status, String declaredHash, String computedHash) {
            this.status = status;
            this.declaredHash = declaredHash;
            this.computedHash = computedHash;
        }

        static Result of(Status s) {
            return new Result(s, null, null);
        }
    }

    private RootfsIntegrity() {
        // Static utility; not instantiable.
    }

    /** Verify integrity of the archive at {@code archivePath} (.tar or .tar.gz). */
    public static Result verify(String archivePath) {
        final boolean isGzip = archivePath.toLowerCase(Locale.US).endsWith(".gz");
        try (InputStream rawFile = new FileInputStream(archivePath);
             InputStream in = isGzip
                     ? new GZIPInputStream(new BufferedInputStream(rawFile))
                     : new BufferedInputStream(rawFile)) {

            final RootfsTreeHash.Accumulator acc = new RootfsTreeHash.Accumulator();
            final String integrityNorm = RootfsTreeHash.norm(INTEGRITY_MEMBER);
            boolean integritySeen = false;
            String declaredAlgo = null;
            String declaredHash = null;

            final byte[] header = new byte[512];
            String longName = null;   // pending GNU 'L'
            String longLink = null;   // pending GNU 'K'
            String paxPath = null;    // pending pax path=
            String paxLink = null;    // pending pax linkpath=

            while (true) {
                if (!readFully(in, header, 512)) {
                    break;
                }
                if (isAllZero(header)) {
                    break; // end-of-archive
                }

                final char typeflag = (char) (header[156] & 0xFF);
                final long size = parseSize(header, 124, 12);
                if (size < 0) {
                    return Result.of(Status.ERROR);
                }
                final long padded = ((size + 511) / 512) * 512;

                // --- meta entries that decorate the NEXT logical member ---
                if (typeflag == 'L') { // GNU long name
                    longName = stripTrailingNul(readBlock(in, size, padded));
                    continue;
                }
                if (typeflag == 'K') { // GNU long link target
                    longLink = stripTrailingNul(readBlock(in, size, padded));
                    continue;
                }
                if (typeflag == 'x') { // pax extended header (this entry)
                    String[] pl = parsePax(readBlock(in, size, padded));
                    if (pl[0] != null) paxPath = pl[0];
                    if (pl[1] != null) paxLink = pl[1];
                    continue;
                }
                if (typeflag == 'g') { // pax GLOBAL header — ignore content
                    skipFully(in, padded);
                    continue;
                }

                // --- resolve the effective name/linkname for this logical member ---
                String name = (paxPath != null) ? paxPath
                            : (longName != null) ? longName
                            : ustarName(header);
                String linkRaw = (paxLink != null) ? paxLink
                              : (longLink != null) ? longLink
                              : cString(header, 157, 100);
                longName = longLink = paxPath = paxLink = null; // consumed

                if (integrityNorm.equals(RootfsTreeHash.norm(name))) {
                    // The integrity member itself: read its declaration, do NOT hash it.
                    byte[] decl = readBlock(in, Math.min(size, MAX_DECL_BYTES), padded);
                    integritySeen = true;
                    String[] av = parseDeclaration(decl);
                    declaredAlgo = av[0];
                    declaredHash = av[1];
                    continue;
                }

                final char memberType = mapType(typeflag);
                if (memberType == 0) {
                    // char/block/fifo or unknown -> unhashable per the recipe.
                    Log.w(TAG, "Unhashable member type '" + typeflag + "' for " + name);
                    return Result.of(Status.ERROR);
                }

                if (memberType == RootfsTreeHash.TYPE_FILE) {
                    LimitedInputStream content = new LimitedInputStream(in, size);
                    try {
                        acc.addMember(name, memberType, null, content);
                    } catch (RootfsTreeHash.UnhashableMemberException e) {
                        return Result.of(Status.ERROR);
                    }
                    skipFully(in, padded - size); // padding after the (now drained) content
                } else {
                    try {
                        acc.addMember(name, memberType, linkRaw, null);
                    } catch (RootfsTreeHash.UnhashableMemberException e) {
                        return Result.of(Status.ERROR);
                    }
                    skipFully(in, padded); // dirs/links carry no content payload
                }
            }

            if (!integritySeen) {
                return Result.of(Status.ABSENT);
            }
            if ("none".equals(declaredAlgo)) {
                return Result.of(Status.DECLARED_NONE);
            }
            if (!RootfsTreeHash.ALGO.equals(declaredAlgo) || declaredHash == null) {
                return Result.of(Status.ERROR); // unknown algo or missing hash
            }
            final String computed = acc.finish();
            final boolean match = computed.equalsIgnoreCase(declaredHash.trim());
            return new Result(match ? Status.MATCH : Status.MISMATCH, declaredHash, computed);

        } catch (IOException e) {
            Log.w(TAG, "Integrity verify I/O error: " + e.getMessage());
            return Result.of(Status.ERROR);
        } catch (Exception e) {
            Log.w(TAG, "Integrity verify error: " + e.getMessage());
            return Result.of(Status.ERROR);
        }
    }

    // --- tar helpers (ustar + GNU + pax), dependency-free ---

    private static char mapType(char t) {
        switch (t) {
            case '0':
            case '\0':
            case '7':            // contiguous == regular (matches Python isreg)
                return RootfsTreeHash.TYPE_FILE;
            case '5':
                return RootfsTreeHash.TYPE_DIR;
            case '2':
                return RootfsTreeHash.TYPE_SYMLINK;
            case '1':
                return RootfsTreeHash.TYPE_HARDLINK;
            default:
                return 0; // char/block/fifo/sparse/unknown -> unhashable
        }
    }

    private static String ustarName(byte[] h) {
        String name = cString(h, 0, 100);
        String prefix = cString(h, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /** Read {@code size} content bytes (consuming pad-size separately is the caller's job here we read full block). */
    private static byte[] readBlock(InputStream in, long size, long padded) throws IOException {
        int n = (int) size;
        byte[] buf = new byte[n];
        if (!readFully(in, buf, n)) {
            throw new IOException("short read");
        }
        skipFully(in, padded - size);
        return buf;
    }

    /** pax records: "<len> key=value\n" repeated. Returns {path, linkpath}. */
    private static String[] parsePax(byte[] data) {
        String path = null;
        String link = null;
        int i = 0;
        final int n = data.length;
        while (i < n) {
            int sp = i;
            while (sp < n && data[sp] != ' ') sp++;
            if (sp >= n) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, i, sp - i, "UTF-8").trim());
            } catch (Exception e) {
                break;
            }
            if (len <= 0 || i + len > n) break;
            // record body is between the space and the trailing newline
            String body;
            try {
                body = new String(data, sp + 1, (i + len) - (sp + 1) - 1, "UTF-8");
            } catch (Exception e) {
                break;
            }
            int eq = body.indexOf('=');
            if (eq > 0) {
                String key = body.substring(0, eq);
                String val = body.substring(eq + 1);
                if ("path".equals(key)) path = val;
                else if ("linkpath".equals(key)) link = val;
            }
            i += len;
        }
        return new String[]{path, link};
    }

    private static String[] parseDeclaration(byte[] json) {
        try {
            JSONObject o = new JSONObject(new String(json, "UTF-8"));
            return new String[]{o.optString("algo", null), o.optString("treehash", null)};
        } catch (Exception e) {
            return new String[]{null, null};
        }
    }

    private static String stripTrailingNul(byte[] b) {
        int end = b.length;
        while (end > 0 && b[end - 1] == 0) end--;
        try {
            return new String(b, 0, end, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        try {
            return new String(b, off, end - off, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Octal size, or GNU base-256 (high bit set) for large files. -1 on garbage. */
    private static long parseSize(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // base-256 binary
            long v = b[off] & 0x7F;
            for (int i = off + 1; i < off + len; i++) {
                v = (v << 8) | (b[i] & 0xFF);
            }
            return v;
        }
        long val = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (any) break;
                continue;
            }
            if (c < '0' || c > '7') return -1;
            val = (val << 3) + (c - '0');
            any = true;
        }
        return any ? val : 0;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) return false;
            off += r;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] tmp = new byte[8192];
        while (left > 0) {
            int r = in.read(tmp, 0, (int) Math.min(tmp.length, left));
            if (r == -1) return;
            left -= r;
        }
    }

    /** Reads at most {@code limit} bytes from {@code in}; never closes {@code in}. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b != -1) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r != -1) left -= r;
            return r;
        }

        @Override
        public void close() {
            // Intentionally does NOT close the shared tar stream.
        }
    }
}
