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

    /** The app whose server name is already stable and human-readable: a ZIM is named by its own
     *  filename convention and Kiwix addresses it by that name, so it is kept as-is. Every other
     *  app's suggested name is an opaque id, so its archive is renamed from the item title. */
    private static final String APP_KEEPS_NATIVE_NAME = "kiwix";

    /** Length of the short id suffix appended after a readable title. */
    private static final int SHORT_ID_LEN = 8;

    /**
     * Resolve the name to store this item under, and record it in {@code used}.
     *
     * <p>Kiwix keeps its native, already-readable name; every other app's opaque id is replaced by
     * a readable {@code "<title>-<short id>.<ext>"} name. The manifest keeps the full identity, so
     * only the on-drive file name changes here.
     *
     * @param title     the item title, used to build a readable name; may be null/empty
     * @param suggested the server-suggested name ({@code Content-Disposition}); may be null/empty
     * @param ref       the item address: its app selects the rule, its localRef the short id
     * @param used      names already taken in this bundle; the returned name is added to it
     */
    public static String forItem(String title, String suggested, ItemRef ref, Set<String> used) {
        String app = ref != null ? ref.app() : null;
        if (APP_KEEPS_NATIVE_NAME.equals(app)) {
            return resolve(suggested, ref, used);
        }
        return readable(title, suggested, ref, used);
    }

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

    /**
     * Build a readable name {@code "<title>-<short id>.<ext>"} for an item whose server-suggested
     * name is an opaque id. The title keeps spaces and accents (exFAT / FAT store Unicode), only the
     * FAT-illegal characters are stripped; the short id is the first {@value #SHORT_ID_LEN}
     * characters of the localRef, lowercased; the extension is taken from the server-suggested name.
     * Falls back to {@link #resolve} when the title is null, empty, or sanitizes to nothing.
     *
     * @param title     the item title; may be null/empty
     * @param suggested the server-suggested name, used only for its extension; may be null/empty
     * @param ref       the item address, used for the short id and the fallback name
     * @param used      names already taken in this bundle; the returned name is added to it
     */
    public static String readable(String title, String suggested, ItemRef ref, Set<String> used) {
        String base = safeTitle(title);
        if (base == null) {
            return resolve(suggested, ref, used);
        }
        String shortId = shortId(ref != null ? ref.localRef() : null);
        String ext = extensionOf(suggested);
        String candidate = base + (shortId != null ? "-" + shortId : "") + ext;
        String unique = deduplicate(candidate, used);
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
        return sanitize(name);
    }

    /** Make a free-text label FAT-safe without the path-strip of {@link #safeBaseName}: a "/" in a
     *  title (e.g. "Grade 1/2 Math") is dropped as an illegal character, not treated as a separator
     *  that truncates the name. Returns null when nothing usable remains. */
    public static String safeTitle(String raw) {
        return raw == null ? null : sanitize(raw);
    }

    /** Drop illegal and control characters, trim, and strip leading dots. Returns null when empty. */
    private static String sanitize(String name) {
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

    /** First {@value #SHORT_ID_LEN} characters of the localRef, lowercased and FAT-safe. Returns
     *  null when nothing usable remains, so the caller drops the suffix. */
    private static String shortId(String localRef) {
        String safe = safeBaseName(localRef);
        if (safe == null) {
            return null;
        }
        String head = safe.length() > SHORT_ID_LEN ? safe.substring(0, SHORT_ID_LEN) : safe;
        head = head.toLowerCase(java.util.Locale.US);
        return head.isEmpty() ? null : head;
    }

    /** The extension (with its dot) of the server-suggested name, or "" when it has none. */
    private static String extensionOf(String suggested) {
        String safe = safeBaseName(suggested);
        if (safe == null) {
            return "";
        }
        int dot = safe.lastIndexOf('.');
        return dot > 0 ? safe.substring(dot) : "";
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
