/*
 * ============================================================================
 * Name        : TarVerboseLine.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-555. Parse one `tar -tv` verbose listing line into its
 *               member path and byte size, so the rootfs Extract phase can
 *               weight progress by bytes written instead of by file count.
 *               File counts make the ETA absurd on slow phones, where rootfs
 *               members vary hugely in size. Pure JVM (no android.*) =>
 *               unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of GNU tar verbose output ({@code tar -tv}), as the bundled libtar (GNU tar 1.35) prints:
 * <pre>MODE OWNER/GROUP SIZE YYYY-MM-DD HH:MM PATH[ -> TARGET]</pre>
 * for example {@code -rw-rw-rw- shell/shell 45678 2026-11-06 08:00 ./sub/file_b.dat}.
 *
 * <p>The parse anchors on the fixed date and time fields, not on whitespace, because the path can
 * contain spaces. It always captures the path, even when the "size" column is not a byte count: a
 * device node prints {@code major,minor} there, and a rootfs carries many device nodes under
 * {@code ./dev}. The path-traversal and rootfs guards read these paths, so dropping a member whose
 * size field is unusual would blind those guards. Only a regular file carries its own data; a
 * directory, symlink, hardlink or device node contributes 0 bytes to the total. The symlink or
 * hardlink target is stripped from the path so it matches the plain path the extract pass
 * ({@code tar -xv}) prints, which lets the extract look its size up by path. A line that is not a
 * member at all (blank, a stray warning) parses to {@code null}, which the caller counts as 0 bytes.
 */
public final class TarVerboseLine {

    // mode, owner/group, size-field (any token: a byte count or a device "major,minor"), date, time
    // (optional seconds), then the rest (the path, which may contain spaces). The size field is lazy
    // so it stops at the first date-shaped token, which is the member mtime, never a date in the path.
    private static final Pattern LINE = Pattern.compile(
            "^(\\S+)\\s+\\S+\\s+(.+?)\\s+\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(?::\\d{2})?\\s+(.+)$");

    /** The member path, exactly as the extract pass prints it (link target stripped). */
    public final String path;
    /** Bytes of this member's own data: 0 for a directory, symlink, hardlink or device node. */
    public final long size;

    private TarVerboseLine(String path, long size) {
        this.path = path;
        this.size = size;
    }

    /** Parse one verbose listing line, or {@code null} when it is not a member line. */
    public static TarVerboseLine parse(String line) {
        if (line == null) return null;
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        final char type = m.group(1).charAt(0);
        String path = m.group(3);
        long size = 0L;
        if (type == 'l') {                    // symlink: "path -> target"
            path = stripFrom(path, " -> ");
        } else if (type == 'h') {             // hardlink: "path link to target" (data counted at the target)
            path = stripFrom(path, " link to ");
        } else if (type == '-') {             // regular file: the only type that carries its own data
            size = parseSize(m.group(2));
        }
        // directory, device node, fifo, socket: path kept for the guards, size stays 0.
        return new TarVerboseLine(path, size);
    }

    /** A regular file's size is a plain decimal; anything else (a device "major,minor") is 0 bytes. */
    private static long parseSize(String field) {
        if (field.isEmpty()) return 0L;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c < '0' || c > '9') return 0L;
        }
        try {
            return Long.parseLong(field);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String stripFrom(String path, String sep) {
        int i = path.indexOf(sep);
        return i >= 0 ? path.substring(0, i) : path;
    }
}
