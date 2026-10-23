package org.appdevforall.k2go.contentexchange.domain;

import java.util.Set;

/**
 * Pure rules for the name each item's archive is written under in the bundle.
 *
 * <p>The server suggests a name through {@code Content-Disposition}, but it arrives over the
 * wire, so it is sanitized to a safe basename (no path separators, no characters a FAT / exFAT
 * drive rejects, no control characters) before it touches the destination. An empty or unusable
 * suggestion falls back to a name built from the item address. Names are de-duplicated so two
 * items can never collide on one file.
 *
 * <p>Stateless except for the {@code used} set the caller threads through, so it is unit-testable
 * on a plain JVM.
 */
public final class ExportFilenames {

    private ExportFilenames() {}

    /** Characters not allowed in a FAT / exFAT file name, plus the path separators. */
    private static final String ILLEGAL = "\\/:*?\"<>|";

    /**
     * Resolve the name to store this item under, and record it in {@code used}.
     *
     * @param suggested the server-suggested name ({@code Content-Disposition}); may be null/empty
     * @param ref       the item address, used to build a fallback name
     * @param used      names already taken in this bundle; the returned name is added to it
     */
    public static String resolve(String suggested, ItemRef ref, Set<String> used) {
        String base = safeBaseName(suggested);
        if (base == null) {
            base = fallback(ref);
        }
        String unique = deduplicate(base, used);
        used.add(unique);
        return unique;
    }

    /** Strip the path, drop illegal and control characters, trim. Returns null when nothing remains.
     *  Public so the import staging path reuses the one "safe FAT basename" rule. */
    public static String safeBaseName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || ILLEGAL.indexOf(c) >= 0) {
                continue;
            }
            out.append(c);
        }
        // Leading dots hide the file on unix-like viewers and "." / ".." are not real names.
        String trimmed = out.toString().trim();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String fallback(ItemRef ref) {
        String app = safeBaseName(ref != null ? ref.app() : null);
        String local = safeBaseName(ref != null ? ref.localRef() : null);
        String base = (app != null ? app : "item") + "_" + (local != null ? local : "payload");
        return base + ".bin";
    }

    /** Append {@code _2}, {@code _3}, ... before the extension until the name is unused. */
    private static String deduplicate(String name, Set<String> used) {
        if (!used.contains(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + "_" + n + ext;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }
}
