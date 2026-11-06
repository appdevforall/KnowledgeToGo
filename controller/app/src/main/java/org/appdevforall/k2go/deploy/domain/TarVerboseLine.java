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
 * <p>The path can contain spaces, so the parse anchors on the fixed date and time fields rather than
 * splitting on whitespace. Only a regular file carries its own data; a directory, symlink or hardlink
 * reports (or is given) size 0, so it adds nothing to the byte total. The symlink/hardlink target is
 * stripped from the path so it matches the plain path the extract pass ({@code tar -xv}) prints, which
 * lets the extract look its size up by path. An unmatched line (blank, a stray warning, a device node
 * whose "major,minor" is not a byte size) parses to {@code null}, which the caller counts as 0 bytes.
 */
public final class TarVerboseLine {

    // mode, owner/group, size, date, time, then the rest (the path, which may contain spaces).
    private static final Pattern LINE = Pattern.compile(
            "^(\\S+)\\s+\\S+\\s+(\\d+)\\s+\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}\\s+(.+)$");

    /** The member path, exactly as the extract pass prints it (link target stripped). */
    public final String path;
    /** Bytes of this member's own data: 0 for a directory, symlink or hardlink. */
    public final long size;

    private TarVerboseLine(String path, long size) {
        this.path = path;
        this.size = size;
    }

    /** Parse one verbose listing line, or {@code null} when it is not a sizeable member line. */
    public static TarVerboseLine parse(String line) {
        if (line == null) return null;
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        final char type = m.group(1).charAt(0);
        long size;
        try {
            size = Long.parseLong(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        String path = m.group(3);
        if (type == 'l') {                    // symlink: "path -> target"
            path = stripFrom(path, " -> ");
            size = 0L;
        } else if (type == 'h') {             // hardlink: "path link to target" (data counted at the target)
            path = stripFrom(path, " link to ");
            size = 0L;
        } else if (type != '-') {             // directory, fifo, etc.: no data of its own
            size = 0L;
        }
        return new TarVerboseLine(path, size);
    }

    private static String stripFrom(String path, String sep) {
        int i = path.indexOf(sep);
        return i >= 0 ? path.substring(0, i) : path;
    }
}
