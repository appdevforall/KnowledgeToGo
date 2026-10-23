package org.appdevforall.k2go.contentexchange.domain;

import java.util.Collections;
import java.util.List;

/**
 * The items read from a bundle on the drive: the parsed manifest entries, whether a real
 * {@code manifest.json} was found (false means they were recovered by scanning the folder), and the
 * names of files that could not be recognized without a manifest (reported, not fatal).
 *
 * <p>Immutable. Partial is first-class: an empty entry list with {@code hadManifest == false} means a
 * folder with nothing recognizable in it.
 */
public final class ImportManifest {

    private final List<ImportEntry> entries;
    private final boolean hadManifest;
    private final List<String> unrecognized;

    public ImportManifest(List<ImportEntry> entries, boolean hadManifest, List<String> unrecognized) {
        this.entries = Collections.unmodifiableList(entries);
        this.hadManifest = hadManifest;
        this.unrecognized = Collections.unmodifiableList(unrecognized);
    }

    public List<ImportEntry> entries() {
        return entries;
    }

    /** True when a {@code manifest.json} was present; false when the items were scanned from the folder. */
    public boolean hadManifest() {
        return hadManifest;
    }

    /** Files in the folder that carry no app/identity without a manifest (e.g. a bare {@code .tar}). */
    public List<String> unrecognized() {
        return unrecognized;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
