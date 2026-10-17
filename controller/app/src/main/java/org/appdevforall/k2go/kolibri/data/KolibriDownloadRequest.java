/*
 * ============================================================================
 * Name        : KolibriDownloadRequest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-475. The POST /k2go-api/kolibri/download body for one
 *               channel selection, sent through the shared RestContentClient.
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import org.appdevforall.k2go.kolibri.domain.ChannelSelection;
import org.json.JSONArray;
import org.json.JSONObject;

public final class KolibriDownloadRequest {

    private KolibriDownloadRequest() {
    }

    /**
     * {@code {items:[{channelId, nodeIds?}]}}.
     *
     * <p>The channel name is not sent: the box resolves it from its own Studio proxy.
     * {@code nodeIds} is omitted for a whole channel, because an empty array means zero
     * nodes to Kolibri, which finishes clean and downloads nothing. No {@code allThumbnails}:
     * the thumbnails of every topic in the channel cost far more than a small pick (K2GO-475).
     */
    public static JSONObject body(ChannelSelection s) throws Exception {
        JSONObject item = new JSONObject().put("channelId", s.channelId());
        if (!s.nodeIds().isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String n : s.nodeIds()) {
                arr.put(n);
            }
            item.put("nodeIds", arr);
        }
        return new JSONObject().put("items", new JSONArray().put(item));
    }

    /**
     * What the box could not deliver, from the job {@code result}: resources Kolibri did
     * not make available plus picked nodes the channel no longer has. 0 unless the box
     * judged the import partial: the box owns that rule, the app only counts for display.
     */
    public static int missing(JSONObject result) {
        if (result == null || !"partial".equals(result.optString("outcome", ""))) {
            return 0;
        }
        int requested = Math.max(0, result.optInt("requested", 0));
        int available = Math.max(0, result.optInt("available", 0));
        int notFound = Math.max(0, result.optInt("notFound", 0));
        return Math.max(0, requested - available) + notFound;
    }
}
