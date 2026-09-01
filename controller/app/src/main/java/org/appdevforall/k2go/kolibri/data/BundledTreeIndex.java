/*
 * ============================================================================
 * Name        : BundledTreeIndex.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure, in-memory index over the flat offline topic-tree bundle
 *               (kolibri_tree.jsonl, "topics" variant). Serves one level at a
 *               time, the shape TreeSource wants, without Android or I/O so it
 *               is unit-testable on a plain JVM (ADFA-5094).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import org.appdevforall.k2go.kolibri.domain.ChannelId;
import org.appdevforall.k2go.kolibri.domain.TopicNode;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The offline topic tree, flattened.
 *
 * <p>The bundle is one JSON object per line, no header — each a <em>folder</em> with the
 * aggregates the generator measured over the whole published content DB: {@code count}
 * (subtree resource count), {@code bytes} (subtree bytes), and {@code dcount}/{@code dbytes}
 * (the folder's direct loose-resource leaves, folded so the bundle need not carry them). A
 * row names its {@code parent}; the channel roots carry an empty parent.
 *
 * <p>Built once via {@link Builder} (fed line by line, so the ~MB file never lands in a
 * single list) and then queried by node id. {@link #fetchOneLevel} returns that node with
 * its direct child folders — one level, matching {@link TreeSource#fetchTree} — each child
 * carrying its own aggregates so the picker shows sizes before drilling. Individual leaf
 * resources are not in the bundle; the live source fills them when online.
 *
 * <p>Never throws: a malformed, duplicate, or unidentifiable line is skipped, and an
 * unknown node id is a {@code null} return.
 */
public final class BundledTreeIndex {

    /** A folder row, ids already normalised. */
    private static final class Row {
        final String id;
        final String title;
        final String kind;
        final int count;
        final long bytes;
        final int dcount;
        final long dbytes;

        Row(String id, String title, String kind, int count, long bytes, int dcount, long dbytes) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.count = count;
            this.bytes = bytes;
            this.dcount = dcount;
            this.dbytes = dbytes;
        }
    }

    private final Map<String, Row> byId;
    private final Map<String, List<Row>> childrenByParent;

    private BundledTreeIndex(Map<String, Row> byId, Map<String, List<Row>> childrenByParent) {
        this.byId = byId;
        this.childrenByParent = childrenByParent;
    }

    /** How many folders the index holds. */
    public int size() {
        return byId.size();
    }

    /**
     * The node at {@code nodeId} with its direct child folders, or {@code null} when the
     * bundle does not carry that node. Children are one level deep (no grandchildren).
     */
    public TopicNode fetchOneLevel(String nodeId) {
        String id = ChannelId.normalise(nodeId);
        if (id == null) {
            return null;
        }
        Row self = byId.get(id);
        if (self == null) {
            return null;
        }
        List<Row> childRows = childrenByParent.get(id);
        List<TopicNode> kids = new ArrayList<>();
        if (childRows != null) {
            for (Row c : childRows) {
                TopicNode child = TopicNode.fromBundle(
                        c.id, c.title, c.kind, c.bytes, c.count, c.dcount, c.dbytes, null);
                if (child != null) {
                    kids.add(child);
                }
            }
        }
        return TopicNode.fromBundle(
                self.id, self.title, self.kind, self.bytes, self.count, self.dcount, self.dbytes, kids);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates rows line by line; see {@link #add}. */
    public static final class Builder {
        private final Map<String, Row> byId = new HashMap<>();
        private final Map<String, List<Row>> childrenByParent = new LinkedHashMap<>();
        private int skipped;

        /**
         * Feeds one JSONL line. Blank lines are ignored; a line that will not parse, carries
         * no usable id, or repeats an id already seen is counted in {@link #skipped()} and
         * dropped. Returns {@code this} for chaining.
         */
        public Builder add(String line) {
            if (line == null) {
                return this;
            }
            String t = line.trim();
            if (t.isEmpty()) {
                return this;
            }
            try {
                JSONObject o = new JSONObject(t);
                String id = ChannelId.normalise(o.optString("id", ""));
                if (id == null || byId.containsKey(id)) {
                    skipped++;
                    return this;
                }
                String parent = ChannelId.normalise(o.optString("parent", ""));
                String parentKey = parent == null ? "" : parent; // roots have no parent
                Row r = new Row(id,
                        o.optString("title", ""),
                        o.optString("kind", ""),
                        Math.max(0, o.optInt("count", 0)),
                        Math.max(0L, o.optLong("bytes", 0L)),
                        Math.max(0, o.optInt("dcount", 0)),
                        Math.max(0L, o.optLong("dbytes", 0L)));
                byId.put(id, r);
                List<Row> siblings = childrenByParent.get(parentKey);
                if (siblings == null) {
                    siblings = new ArrayList<>();
                    childrenByParent.put(parentKey, siblings);
                }
                siblings.add(r);
            } catch (Exception badLine) {
                skipped++;
            }
            return this;
        }

        /** Lines dropped so far. */
        public int skipped() {
            return skipped;
        }

        public BundledTreeIndex build() {
            return new BundledTreeIndex(
                    Collections.unmodifiableMap(byId),
                    Collections.unmodifiableMap(childrenByParent));
        }
    }
}
