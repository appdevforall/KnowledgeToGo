package org.appdevforall.k2go.contentexchange.domain;

import java.util.Collections;
import java.util.List;

/**
 * One app's content as the manager renders it: the app key, its items, and the
 * sum of their sizes. An installed app with no content is a valid empty group
 * (so the manager can show "Kiwix: no items"), which is why {@code app} comes
 * from the installed-apps list, not only from the items.
 *
 * <p>Immutable value object. Pure domain type.
 */
public final class ContentGroup {

    private final String app;
    private final List<ContentItem> items;
    private final long totalBytes;

    public ContentGroup(String app, List<ContentItem> items, long totalBytes) {
        this.app = app;
        this.items = Collections.unmodifiableList(items);
        this.totalBytes = totalBytes;
    }

    public String app() {
        return app;
    }

    /** The app's items (never null; may be empty for an installed-but-empty app). */
    public List<ContentItem> items() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Sum of the items' sizes in bytes. */
    public long totalBytes() {
        return totalBytes;
    }
}
