/*
 * ============================================================================
 * Name        : CxCoreDeleteSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Data-layer implementation of the delete source port against
 *               cx-core (through dash-node's /v1 relay): DELETE /v1/content/{app}/{localRef}.
 *               Maps the core JSON {ok, freedBytes} | {ok:false, reason} onto the domain
 *               DeleteResult, so the domain stays JSON-free.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ContentDeleteSource;
import org.appdevforall.k2go.contentexchange.domain.DeleteResult;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.json.JSONObject;

/**
 * Deletes one item on the box and maps the response to {@link DeleteResult}.
 *
 * <p>Stateless and thread-confined to the delete worker. A transport failure (box unreachable, a
 * 4xx/5xx) throws; a reached box that could not delete the item returns {@code {ok:false, reason}},
 * which maps to {@link DeleteResult#failure}.
 */
public final class CxCoreDeleteSource implements ContentDeleteSource {

    private final CxCoreDataSource dataSource;

    public CxCoreDeleteSource(CxCoreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DeleteResult delete(ItemRef ref) throws Exception {
        JSONObject response = dataSource.deleteContent(ref.app(), ref.localRef());
        if (response.optBoolean("ok", false)) {
            return DeleteResult.ok(response.optLong("freedBytes", 0L));
        }
        return DeleteResult.failure(response.optString("reason", response.optString("error", "")));
    }
}
