/*
 * ============================================================================
 * Name        : RootfsTreeHash.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure-JVM implementation of the "iiab-tree-sha256-v1" rootfs
 *               integrity digest. Byte-for-byte compatible with the frozen
 *               reference tools/iiab_tree_hash.py (spec: docs/ROOTFS_MANIFEST.md).
 *
 *               DOMAIN layer: no Android, no tar library. The data layer feeds it
 *               logical tar members (either as an Iterable<Member> for tests, or
 *               incrementally via Accumulator for a single-pass tar stream). This
 *               class owns everything the spec defines (path normalization,
 *               per-member digest, order-independent domain-separated combine) so
 *               byte-parity stays testable here.
 * ============================================================================
 */
package org.iiab.controller.deploy.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Recomputes the {@code iiab-tree-sha256-v1} treehash over a tar's logical members.
 *
 * <p>Per-member digest:
 * <pre>
 *   digest(m) = SHA256( norm(name) + 0x00 + type + 0x00 + payload )
 *     'f' regular  : payload = file content        (no trailing 0x00)
 *     'd' directory: payload = ""                  (empty)
 *     'l' symlink  : payload = raw linktarget + 0x00
 *     'h' hardlink : payload = norm(linktarget) + 0x00
 * </pre>
 * Any other tar member type is unhashable and aborts with
 * {@link UnhashableMemberException} (mirrors the reference tool's exit 3 -> the
 * caller maps this to a CORRUPT result, fail-closed).
 *
 * <p>Combine (order-independent, domain-separated):
 * <pre>
 *   treehash = lowercase_hex( SHA256( "iiab-tree-sha256-v1" + 0x00
 *                                     + concat( digest(m_i) sorted ascending
 *                                               by raw 32-byte value ) ) )
 * </pre>
 */
public final class RootfsTreeHash {

    /** Algorithm identifier; also the domain-separation prefix in the combine. */
    public static final String ALGO = "iiab-tree-sha256-v1";

    public static final char TYPE_FILE = 'f';
    public static final char TYPE_DIR = 'd';
    public static final char TYPE_SYMLINK = 'l';
    public static final char TYPE_HARDLINK = 'h';

    private RootfsTreeHash() {
        // Static utility; not instantiable.
    }

    /** A single logical member of the tar (used by the Iterable test path). */
    public interface Member {
        String name();
        char type();
        String linkTarget();
        InputStream openContent() throws IOException;
    }

    /** Thrown when a member type is not one of f/d/l/h (-> CORRUPT, fail-closed). */
    public static final class UnhashableMemberException extends Exception {
        public UnhashableMemberException(String message) {
            super(message);
        }
    }

    /**
     * Incremental accumulator for single-pass tar streams. Call
     * {@link #addMember} for each logical member in iteration order (the data
     * layer skips the integrity member itself), then {@link #finish}. The content
     * stream passed to addMember is DRAINED but never closed, so a shared
     * {@code TarArchiveInputStream} stays usable for the next entry.
     */
    public static final class Accumulator {
        private final List<byte[]> digests = new ArrayList<>();

        public void addMember(String name, char type, String linkTarget, InputStream content)
                throws IOException, UnhashableMemberException {
            digests.add(memberDigest(norm(name), type, linkTarget, content));
        }

        public String finish() {
            Collections.sort(digests, UNSIGNED_LEX);
            final MessageDigest fin = newSha256();
            fin.update(ALGO.getBytes(StandardCharsets.UTF_8));
            fin.update((byte) 0x00);
            for (byte[] d : digests) {
                fin.update(d);
            }
            return toHex(fin.digest());
        }
    }

    /**
     * Canonical path normalization (identical to {@code norm()} in the reference):
     * UTF-8; {@code \} -> {@code /}; strip ONE leading {@code ./}; strip ALL leading
     * {@code /}; strip ALL trailing {@code /}. No whitespace trimming.
     */
    public static String norm(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("./")) {
            n = n.substring(2);
        }
        int s = 0;
        while (s < n.length() && n.charAt(s) == '/') {
            s++;
        }
        int e = n.length();
        while (e > s && n.charAt(e - 1) == '/') {
            e--;
        }
        return n.substring(s, e);
    }

    /**
     * Convenience for tests / in-memory members: compute over an Iterable,
     * skipping the member whose normalized name equals {@code norm(excludeName)}.
     */
    public static String compute(Iterable<Member> members, String excludeName)
            throws IOException, UnhashableMemberException {
        final String exclude =
                (excludeName == null || excludeName.isEmpty()) ? null : norm(excludeName);
        final Accumulator acc = new Accumulator();
        for (Member m : members) {
            final String name = norm(m.name());
            if (exclude != null && exclude.equals(name)) {
                continue;
            }
            acc.addMember(m.name(), m.type(), m.linkTarget(),
                    m.type() == TYPE_FILE ? m.openContent() : null);
        }
        return acc.finish();
    }

    private static byte[] memberDigest(String normalizedName, char type, String linkTarget,
                                       InputStream content)
            throws IOException, UnhashableMemberException {
        final MessageDigest md = newSha256();
        md.update(normalizedName.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0x00);
        md.update((byte) type);
        md.update((byte) 0x00);

        switch (type) {
            case TYPE_FILE:
                // Drain (do NOT close): the data layer reuses the tar stream.
                final byte[] buf = new byte[1 << 16];
                int r;
                while ((r = content.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
                break;
            case TYPE_DIR:
                break; // empty payload
            case TYPE_SYMLINK:
                md.update(linkTarget.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x00);
                break;
            case TYPE_HARDLINK:
                md.update(norm(linkTarget).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x00);
                break;
            default:
                throw new UnhashableMemberException(
                        "Unhashable tar member type '" + type + "' for " + normalizedName);
        }
        return md.digest();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final Comparator<byte[]> UNSIGNED_LEX = new Comparator<byte[]>() {
        @Override
        public int compare(byte[] a, byte[] b) {
            final int n = Math.min(a.length, b.length);
            for (int i = 0; i < n; i++) {
                final int ai = a[i] & 0xFF;
                final int bi = b[i] & 0xFF;
                if (ai != bi) {
                    return ai - bi;
                }
            }
            return a.length - b.length;
        }
    };

    private static String toHex(byte[] bytes) {
        final char[] hexChars = "0123456789abcdef".toCharArray();
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xFF;
            out[i * 2] = hexChars[v >>> 4];
            out[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(out);
    }
}
