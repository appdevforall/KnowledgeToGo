/*
 * ============================================================================
 * Name        : GuestGitConfig.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The git settings that keep git objects whole under proot --link2symlink.
 *               Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.proot.domain;

import java.util.Locale;

/**
 * K2GO-558: git finalizes new objects with {@code link()}; proot {@code --link2symlink} turns that into
 * a symlink to a path that is then removed, so objects go missing and the clone breaks. With
 * {@code rename()} (and packs kept whole) nothing goes through link2symlink.
 *
 * <p>The rootfs bake writes the same settings into {@code /etc/gitconfig}
 * ({@code tools/rootfs-builder/build-iiab-rootfs.sh}); this rule brings older rootfs trees in line.
 */
public final class GuestGitConfig {

    /** section.key (lower case, as git compares them) -> required value. */
    private static final String[][] REQUIRED = {
            {"core", "createobject", "createObject", "rename"},
            {"transfer", "unpacklimit", "unpackLimit", "1"},
    };

    private GuestGitConfig() {}

    /**
     * The text to append to a system gitconfig so that the required settings take effect, or ""
     * when they already do. Git uses the last value of a key, so an appended block wins.
     */
    public static String toAppend(String existing) {
        StringBuilder out = new StringBuilder();
        for (String[] r : REQUIRED) {
            if (!r[3].equals(lastValue(existing, r[0], r[1]))) {
                out.append('[').append(r[0]).append("]\n\t").append(r[2]).append(" = ").append(r[3]).append('\n');
            }
        }
        if (out.length() > 0 && existing != null && !existing.isEmpty() && !existing.endsWith("\n")) {
            out.insert(0, '\n');
        }
        return out.toString();
    }

    /** The last value of {@code section.key} in a gitconfig text, or null. Subsections are skipped. */
    static String lastValue(String text, String section, String key) {
        if (text == null) {
            return null;
        }
        String current = null;
        String value = null;
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                current = end > 0 ? line.substring(1, end).trim().toLowerCase(Locale.ROOT) : null;
                continue;
            }
            if (!section.equals(current)) {
                continue;
            }
            int eq = line.indexOf('=');
            String k = (eq >= 0 ? line.substring(0, eq) : line).trim().toLowerCase(Locale.ROOT);
            if (k.equals(key)) {
                value = eq >= 0 ? stripComment(line.substring(eq + 1)).trim() : "true";
            }
        }
        return value;
    }

    private static String stripComment(String v) {
        int c = v.indexOf('#');
        int s = v.indexOf(';');
        int cut = c < 0 ? s : (s < 0 ? c : Math.min(c, s));
        return cut < 0 ? v : v.substring(0, cut);
    }
}
