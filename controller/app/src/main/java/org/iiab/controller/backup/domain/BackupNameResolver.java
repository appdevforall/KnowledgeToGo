/*
 * ============================================================================
 * Name        : BackupNameResolver.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure rule for keeping an imported backup's exact filename, with
 *               -1/-2/-3 disambiguation on collision.
 * ============================================================================
 */
package org.iiab.controller.backup.domain;

import java.util.Set;

/**
 * Decides the on-disk filename for an imported backup. Keeps the original name
 * EXACTLY; only if that name already exists does it insert {@code -1}, {@code -2}, ...
 * before the extension. Compound extensions ({@code .tar.gz}, {@code .tar.xz}) are
 * preserved. Pure domain logic (no Android, no I/O) so it is fully unit-testable.
 */
public final class BackupNameResolver {

    private static final String FALLBACK = "backup.tar.gz";

    private BackupNameResolver() { }

    /**
     * @param desired  the original filename (e.g. from SAF DISPLAY_NAME); may be null/dirty
     * @param existing the set of filenames already present in the backups dir
     * @return the exact desired name if free, else the same name with -1/-2/... before the ext
     */
    public static String resolve(String desired, Set<String> existing) {
        String name = sanitize(desired);
        if (existing == null || !existing.contains(name)) {
            return name;
        }
        String base = baseName(name);
        String ext = extension(name);
        for (int i = 1; ; i++) {
            String candidate = base + "-" + i + ext;
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** Strip any path, trim, and fall back to a safe default when empty. */
    static String sanitize(String name) {
        if (name == null) return FALLBACK;
        String n = name.trim();
        int sep = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (sep >= 0) n = n.substring(sep + 1);
        return n.isEmpty() ? FALLBACK : n;
    }

    /** Compound-extension aware: {@code .tar.gz}/{@code .tar.xz}, else the last {@code .ext}. */
    static String extension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz")) {
            return name.substring(name.length() - 7);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    static String baseName(String name) {
        return name.substring(0, name.length() - extension(name).length());
    }
}
