/*
 * ============================================================================
 * Name        : StudioCatalogMapper.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure JSON -> domain translation for Kolibri Studio's public API.
 *               No network, no Android: unit-testable on a plain JVM (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.TopicNode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates Studio's public JSON into domain entities.
 *
 * <p>Split from {@link StudioCatalogSource} for the same reason
 * {@code kolibri.map.ts} is split from {@code kolibri.exec.ts}: there is no
 * network here, so every field-shape trap can be tested without a server.
 *
 * <p>Each method encapsulates a concrete difference between what Studio sends
 * and what the app needs. Field names below are Studio's v2 names and are
 * deliberately not "tidied": Kolibri's own proxy renames several of them
 * ({@code total_resource_count} becomes {@code total_resources},
 * {@code published_size} becomes {@code total_file_size}), so any renaming here
 * would make the two impossible to tell apart later.
 */
public final class StudioCatalogMapper {

    private StudioCatalogMapper() {
    }

    /**
     * Reads a page of {@code GET /api/public/v2/channel/}.
     *
     * <p>The payload is {@code {page, count, total_pages, results: [...]}}.
     * A bare array is also accepted, because the v1 endpoint returns one and a
     * caller pointed at the wrong version should degrade rather than crash.
     *
     * <p>Rows that fail to yield a usable channel are skipped, not fatal: one
     * malformed entry should cost that entry, not the whole catalog.
     */
    public static List<Channel> channels(JSONObject page) {
        if (page == null) {
            return new ArrayList<>();
        }
        return channelArray(page.optJSONArray("results"));
    }

    /** @see #channels(JSONObject) */
    public static List<Channel> channelArray(JSONArray rows) {
        List<Channel> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.length(); i++) {
            Channel c = channel(rows.optJSONObject(i));
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** One channel row, or null when it carries no usable id. */
    public static Channel channel(JSONObject o) {
        if (o == null) {
            return null;
        }
        return Channel.builder(o.optString("id", ""))
                .version(o.optInt("version", 0))
                .name(o.optString("name", ""))
                .description(o.optString("description", ""))
                .author(o.optString("author", ""))
                .language(o.optString("lang_code", ""), o.optString("lang_name", ""))
                .totalResources(o.optInt("total_resource_count", 0))
                // optLong, not optInt: a large channel exceeds 2 GB and would
                // silently wrap to a negative or truncated size.
                .publishedSize(o.optLong("published_size", 0L))
                .rootNodeId(o.optString("root", ""))
                .build();
    }

    /** Total number of channels the query matches, across all pages. -1 when absent. */
    public static int totalCount(JSONObject page) {
        return page == null ? -1 : page.optInt("count", -1);
    }

    /** Total pages for the query. -1 when absent. */
    public static int totalPages(JSONObject page) {
        return page == null ? -1 : page.optInt("total_pages", -1);
    }

    /**
     * Reads {@code GET /api/public/v2/contentnode_tree/<id>} into a subtree.
     *
     * <p>Three shape details this handles:
     * <ul>
     *   <li>Children are nested as {@code children.results[]}, not a bare array,
     *       with a {@code children.more} cursor that is non-null when the level
     *       was paged. A paged level makes the subtree size unknowable from what
     *       arrived, which is passed through so the total is not under-counted.</li>
     *   <li>Sizes live in {@code files[].file_size}. Thumbnail files are excluded:
     *       they are fetched only with {@code all_thumbnails}, so counting them
     *       would over-state a normal import.</li>
     *   <li>{@code lft}/{@code rght} are nested-set bounds. Their span gives the
     *       descendant count for free, without walking or fetching anything.</li>
     * </ul>
     */
    public static TopicNode tree(JSONObject node) {
        if (node == null) {
            return null;
        }

        JSONObject childrenBlock = node.optJSONObject("children");
        List<TopicNode> kids = new ArrayList<>();
        boolean complete = true;
        if (childrenBlock != null) {
            JSONArray results = childrenBlock.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    TopicNode child = tree(results.optJSONObject(i));
                    if (child != null) {
                        kids.add(child);
                    }
                }
            }
            complete = childrenBlock.isNull("more");
        }

        return TopicNode.of(
                node.optString("id", ""),
                node.optString("title", ""),
                node.optString("kind", ""),
                node.optBoolean("is_leaf", false),
                ownBytes(node.optJSONArray("files")),
                descendantCount(node),
                kids,
                complete);
    }

    /**
     * Sum of a node's own content files, excluding thumbnails.
     *
     * <p>A thumbnail is flagged either by {@code thumbnail: true} or by a preset
     * ending in {@code _thumbnail}; both appear in live payloads, so both are
     * checked rather than trusting one.
     */
    static long ownBytes(JSONArray files) {
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) {
                continue;
            }
            if (f.optBoolean("thumbnail", false)
                    || f.optString("preset", "").endsWith("_thumbnail")) {
                continue;
            }
            total += Math.max(0L, f.optLong("file_size", 0L));
        }
        return total;
    }

    /**
     * Descendants below a node, from its nested-set bounds: a node spanning
     * {@code [lft, rght]} contains {@code (rght - lft - 1) / 2} of them.
     * Returns 0 when the bounds are absent or inconsistent.
     */
    static int descendantCount(JSONObject node) {
        if (node == null) {
            return 0;
        }
        int lft = node.optInt("lft", -1);
        int rght = node.optInt("rght", -1);
        if (lft < 0 || rght <= lft) {
            return 0;
        }
        return (rght - lft - 1) / 2;
    }
}
