/*
 * ============================================================================
 * Name        : ShareProgressReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-577. Maps the conex-core share progress JSON to the domain ShareProgress.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ShareProgress;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ShareProgressReader {

    private ShareProgressReader() {
    }

    /** {@code {sessionId, state, items: [{itemRef, movedBytes?, totalBytes?, state?, delivered}]}}.
     *  A box before 0.4.2 sends no state while the session is open. */
    public static ShareProgress parse(JSONObject body) {
        List<ShareProgress.Item> items = new ArrayList<>();
        JSONArray rows = body.optJSONArray("items");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) {
                    items.add(new ShareProgress.Item(row.optLong("movedBytes", -1),
                            row.optLong("totalBytes", -1), row.optBoolean("delivered", false)));
                }
            }
        }
        return new ShareProgress(state(body.optString("state", "open")), items);
    }

    /** The progress of a session the box no longer knows (an older box: no ended record). */
    public static ShareProgress gone() {
        return new ShareProgress(ShareProgress.State.GONE, null);
    }

    private static ShareProgress.State state(String value) {
        switch (value) {
            case "completed":
                return ShareProgress.State.COMPLETED;
            case "cancelled":
                return ShareProgress.State.CANCELLED;
            case "expired":
                return ShareProgress.State.EXPIRED;
            default:
                return ShareProgress.State.OPEN;
        }
    }
}
