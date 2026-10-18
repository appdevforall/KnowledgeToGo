/*
 * ============================================================================
 * Name        : BundleSizedTreeSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-476. Fills the folder sizes Studio cannot give (its tree is
 *               one level deep and carries no folder totals) from the bundle.
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import org.appdevforall.k2go.kolibri.domain.TopicNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A live level with the bundle's folder sizes where the live source has none.
 *
 * <p>The structure and the leaves stay live. A folder whose size is unknown takes the bundle's
 * figure (distinct files, K2GO-476 PR4), and the level is then dated by the older of the two,
 * so the "updated" line never claims more freshness than its numbers have.
 */
public final class BundleSizedTreeSource implements TreeSource {

    /** Where the fill reads folder sizes and their date. */
    public interface Sizes {
        /** A folder's size, or -1 when unknown. */
        long bytesOf(String nodeId);

        /** When those sizes were generated (epoch millis), 0 when unknown. */
        long generatedMs();
    }

    private final TreeSource live;
    private final Sizes bundle;

    public BundleSizedTreeSource(TreeSource live, Sizes bundle) {
        this.live = live;
        this.bundle = bundle;
    }

    @Override
    public TopicNode fetchTree(String nodeId) {
        TopicNode node = live.fetchTree(nodeId);
        if (node == null) {
            return null;
        }
        List<TopicNode> kids = new ArrayList<>();
        boolean filled = false;
        for (TopicNode kid : node.children()) {
            long bytes = kid.isLeaf() || kid.hasSubtreeSize() ? -1L : bundle.bytesOf(kid.id());
            if (bytes >= 0L) {
                TopicNode sized = TopicNode.measured(kid.id(), kid.title(), kid.kind(), bytes,
                        kid.descendantCount(), kid.children());
                kids.add(sized == null ? kid : sized);
                filled = filled || sized != null;
            } else {
                kids.add(kid);
            }
        }
        if (!filled) {
            return node;
        }
        long bundleMs = bundle.generatedMs();
        long asOf = bundleMs <= 0L ? 0L : Math.min(node.asOfMs(), bundleMs);
        return node.withChildren(kids).withAsOf(asOf);
    }
}
