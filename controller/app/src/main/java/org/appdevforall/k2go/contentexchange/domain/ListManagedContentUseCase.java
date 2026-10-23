package org.appdevforall.k2go.contentexchange.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists managed content grouped by app, with per-group and grand totals.
 *
 * <p>This is the business rule the manager renders. The grouping, ordering and
 * totalling live here (not in the UI or the data source), so it is a pure,
 * JVM-unit-testable function of (installed apps, content items).
 *
 * <p>Rules:
 * <ul>
 *   <li>One group per installed app, so an installed-but-empty app still shows.</li>
 *   <li>Items whose app is not in the installed list are not dropped: a trailing
 *       group is created for each such app (defensive against a lagging
 *       {@code /v1/apps}).</li>
 *   <li>Group order: a preferred order ({@code kiwix, kolibri, calibre}) first,
 *       then any other apps alphabetically.</li>
 *   <li>Items within a group are ordered by title (case-insensitive), then by
 *       {@code localRef} as a stable tiebreak.</li>
 *   <li>A group total is the sum of its items' bytes; the grand total is the sum
 *       across all groups.</li>
 * </ul>
 */
public final class ListManagedContentUseCase {

    /** Apps shown first, in this order; any other app follows alphabetically. */
    static final List<String> PREFERRED_ORDER = Arrays.asList("kiwix", "kolibri", "calibre");

    private final ContentRepository repository;

    public ListManagedContentUseCase(ContentRepository repository) {
        this.repository = repository;
    }

    /** Fetches installed apps + content from the box, then groups them. */
    public ManagedContent execute() throws Exception {
        return group(repository.fetchApps(), repository.fetchContent());
    }

    /**
     * Pure grouping: installed apps + items -> grouped content with totals.
     * Static and side-effect-free so tests drive it directly.
     */
    static ManagedContent group(List<String> installedApps, List<ContentItem> items) {
        // Preserve one slot per installed app (empty groups included), in a map that
        // keeps insertion order; orphan-app items append new slots after.
        Map<String, List<ContentItem>> byApp = new LinkedHashMap<>();
        if (installedApps != null) {
            for (String app : installedApps) {
                if (app != null && !byApp.containsKey(app)) {
                    byApp.put(app, new ArrayList<>());
                }
            }
        }
        if (items != null) {
            for (ContentItem item : items) {
                if (item == null) {
                    continue;
                }
                byApp.computeIfAbsent(item.app(), k -> new ArrayList<>()).add(item);
            }
        }

        List<String> appOrder = new ArrayList<>(byApp.keySet());
        appOrder.sort(ListManagedContentUseCase::compareApps);

        List<ContentGroup> groups = new ArrayList<>(appOrder.size());
        long grandTotal = 0L;
        for (String app : appOrder) {
            List<ContentItem> groupItems = byApp.get(app);
            groupItems.sort(ITEM_ORDER);
            long groupTotal = 0L;
            for (ContentItem item : groupItems) {
                groupTotal += Math.max(0L, item.bytes());
            }
            grandTotal += groupTotal;
            groups.add(new ContentGroup(app, groupItems, groupTotal));
        }
        return new ManagedContent(groups, grandTotal);
    }

    /** Preferred apps first in their fixed order, then the rest alphabetically. */
    static int compareApps(String a, String b) {
        int ra = PREFERRED_ORDER.indexOf(a);
        int rb = PREFERRED_ORDER.indexOf(b);
        if (ra >= 0 || rb >= 0) {
            if (ra < 0) {
                return 1;
            }
            if (rb < 0) {
                return -1;
            }
            return Integer.compare(ra, rb);
        }
        return a.compareToIgnoreCase(b);
    }

    private static final Comparator<ContentItem> ITEM_ORDER = (x, y) -> {
        int byTitle = safe(x.title()).compareToIgnoreCase(safe(y.title()));
        if (byTitle != 0) {
            return byTitle;
        }
        return safe(x.localRef()).compareTo(safe(y.localRef()));
    };

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
