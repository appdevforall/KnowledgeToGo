package org.appdevforall.k2go.contentexchange.domain;

import java.util.Collections;
import java.util.List;

/**
 * All managed content on the box, grouped by app, with the grand total of every
 * item's size. This is what {@link ListManagedContentUseCase} produces and the
 * manager screen renders (groups + per-item sizes + total used).
 *
 * <p>Immutable value object. Pure domain type.
 */
public final class ManagedContent {

    private final List<ContentGroup> groups;
    private final long totalBytes;

    public ManagedContent(List<ContentGroup> groups, long totalBytes) {
        this.groups = Collections.unmodifiableList(groups);
        this.totalBytes = totalBytes;
    }

    /** Per-app groups, in display order (never null; may be empty). */
    public List<ContentGroup> groups() {
        return groups;
    }

    /** Sum of every item's size across all groups: the "total used by content". */
    public long totalBytes() {
        return totalBytes;
    }

    public boolean isEmpty() {
        for (ContentGroup g : groups) {
            if (!g.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
