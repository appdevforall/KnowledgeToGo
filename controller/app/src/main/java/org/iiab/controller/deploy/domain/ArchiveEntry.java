/*
 * ============================================================================
 * Name        : ArchiveEntry.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Domain rule: is an archive member name safe to extract, i.e.
 *               does it stay inside the destination directory? Closes
 *               tech-debt item D11 (tar path-traversal on extraction).
 * ============================================================================
 */
package org.iiab.controller.deploy.domain;

/**
 * Pure (framework-free) guard against archive path-traversal ("Zip Slip" for
 * tar). An imported/restored backup is untrusted: a crafted {@code .tar.gz}
 * whose members use {@code ../} or absolute paths could write <em>outside</em>
 * the extraction directory and overwrite app files — tech-debt item <b>D11</b>.
 *
 * <p>No {@code android.*} here, so it is unit-testable on a plain JVM and
 * reusable by any extractor.
 */
public final class ArchiveEntry {

    private ArchiveEntry() {
        // Static utility; not instantiable.
    }

    /**
     * True if extracting {@code name} could escape the destination root, i.e.
     * the member is an absolute path or uses {@code ..} segments that climb
     * above the root. Benign relative paths (including a leading {@code ./} and
     * an internal {@code a/../b} that stays within the tree) return false.
     *
     * <p>Mirrors the safe model "strip leading slash, forbid climbing out": a
     * legitimate distro rootfs / backup uses relative members and never needs
     * to escape, so this rejects attacks without rejecting real archives.
     */
    public static boolean escapesRoot(String name) {
        if (name == null) {
            return false; // ignore blank listing lines, not an escape
        }
        String n = name.replace('\\', '/').trim();
        if (n.isEmpty()) {
            return false;
        }
        if (n.startsWith("/")) {
            return true; // absolute path — would ignore the -C destination
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= n.length(); i++) {
            if (i == n.length() || n.charAt(i) == '/') {
                String seg = n.substring(start, i);
                start = i + 1;
                if (seg.isEmpty() || seg.equals(".")) {
                    continue;
                }
                if (seg.equals("..")) {
                    depth--;
                    if (depth < 0) {
                        return true; // climbed above the root
                    }
                } else {
                    depth++;
                }
            }
        }
        return false;
    }
}
