/*
 * ============================================================================
 * Name        : ConexCoreExportSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427 / K2GO-507. Data-layer implementation of the export source port against
 *               conex-core (through dash-node's /v1 relay): POST /v1/export/plan, the export long
 *               operation (prepare / status / fetch / abort), and the collection-manifest assembly.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ContentExportSource;
import org.appdevforall.k2go.contentexchange.domain.ExportItem;
import org.appdevforall.k2go.contentexchange.domain.ExportPlan;
import org.appdevforall.k2go.contentexchange.domain.ExportPrep;
import org.appdevforall.k2go.contentexchange.domain.ExportStatus;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans an export, streams one item, and builds the collection manifest.
 *
 * <p>The per-item entries the plan returns (identity + dedup + integrity) are captured here so the
 * manifest can be assembled from the authoritative source rather than re-derived. That cache is this
 * object's own copy of its own HTTP response, keyed by {@link ItemRef}: it is not a second source of
 * truth for "what content exists" (the core owns that), only the plan this run already fetched.
 *
 * <p>Instances are single-run and used off the main thread; the capture is not shared.
 */
public final class ConexCoreExportSource implements ContentExportSource {

    private final ConexCoreDataSource dataSource;

    /** Captured plan entries for the manifest, by item, preserving request order. */
    private final Map<ItemRef, JSONObject> entries = new LinkedHashMap<>();

    public ConexCoreExportSource(ConexCoreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public ExportPlan plan(List<ItemRef> refs) throws Exception {
        JSONArray reqItems = new JSONArray();
        for (ItemRef ref : refs) {
            reqItems.put(new JSONObject().put("app", ref.app()).put("localRef", ref.localRef()));
        }
        JSONObject response = dataSource.postExportPlan(new JSONObject().put("items", reqItems));

        entries.clear();
        List<ExportItem> items = new ArrayList<>();
        long total = 0L;
        JSONArray found = response.optJSONArray("items");
        if (found != null) {
            for (int i = 0; i < found.length(); i++) {
                JSONObject entry = found.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                JSONObject identity = entry.optJSONObject("identity");
                String app = entry.optString("app", "");
                String localRef = identity != null ? identity.optString("localRef", "") : "";
                if (app.isEmpty() || localRef.isEmpty()) {
                    continue;
                }
                ItemRef ref = new ItemRef(app, localRef);
                long bytes = entry.optLong("bytes", 0L);
                items.add(new ExportItem(ref, entry.optString("title", localRef), bytes));
                entries.put(ref, entry);
                total += bytes;
            }
        }

        List<String> skipped = new ArrayList<>();
        JSONArray missing = response.optJSONArray("missing");
        if (missing != null) {
            for (int i = 0; i < missing.length(); i++) {
                JSONObject m = missing.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                skipped.add(m.optString("app", "?") + "/" + m.optString("localRef", "?")
                        + " (" + m.optString("reason", "skipped") + ")");
            }
        }
        // Prefer the server total; fall back to the summed item bytes.
        long reported = response.optLong("totalBytes", total);
        return new ExportPlan(items, skipped, reported);
    }

    @Override
    public ExportPrep prepare(ItemRef ref) throws Exception {
        JSONObject r = dataSource.postExportPrepare(ref.app(), ref.localRef());
        String opId = r.optString("opId", "");
        if (opId.isEmpty()) {
            throw new Exception("export prepare returned no opId");
        }
        boolean ready = "ready".equals(r.optString("state", ""));
        return new ExportPrep(opId, ready, r.optLong("totalBytes", 0L));
    }

    @Override
    public ExportStatus status(String opId) throws Exception {
        JSONObject r;
        try {
            r = dataSource.getExportStatus(opId);
        } catch (ConexCoreDataSource.ConexHttpException e) {
            if (e.code() == 404) {
                return ExportStatus.gone();   // the op was swept: the caller re-prepares
            }
            throw e;
        }
        ExportStatus.State state;
        switch (r.optString("state", "")) {
            case "ready":
                state = ExportStatus.State.READY;
                break;
            case "error":
                state = ExportStatus.State.ERROR;
                break;
            default:
                state = ExportStatus.State.STAGING;
                break;
        }
        String error = r.isNull("error") ? null : r.optString("error", null);
        return new ExportStatus(state, r.optLong("stagedBytes", 0L), r.optLong("totalBytes", 0L), error);
    }

    @Override
    public ItemPayload fetch(String opId) throws Exception {
        ConexCoreDataSource.Stream stream = dataSource.openExportFetch(opId);
        return new ItemPayload(stream.body(), stream.filename(), stream);
    }

    @Override
    public void abort(String opId) {
        if (opId == null || opId.isEmpty()) {
            return;
        }
        try {
            dataSource.deleteExportOp(opId);
        } catch (Exception ignored) {
            // Best-effort: a swept or already-dropped op 404s; the box's TTL sweep is the backstop.
        }
    }

    @Override
    public byte[] buildManifest(List<ItemRef> order, Map<ItemRef, String> payloadNames) throws Exception {
        return CollectionManifest.build(entries, order, payloadNames);
    }
}
