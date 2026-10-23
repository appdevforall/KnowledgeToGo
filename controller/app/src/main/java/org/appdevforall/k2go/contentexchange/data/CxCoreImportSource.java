/*
 * ============================================================================
 * Name        : CxCoreImportSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Data-layer implementation of the import source port against cx-core
 *               (through dash-node's /v1 relay): POST /v1/import/validate (per-item verdict),
 *               POST /v1/import/item (ingest one staged item), and DELETE /v1/content (reclaim).
 *               The manifest entries are passed to the core unchanged; the core owns the shape.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ImportSource;
import org.appdevforall.k2go.contentexchange.domain.ImportVerdict;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class CxCoreImportSource implements ImportSource {

    private final CxCoreDataSource dataSource;

    public CxCoreImportSource(CxCoreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<ImportVerdict> validate(List<ImportEntry> entries) throws Exception {
        JSONArray items = new JSONArray();
        for (ImportEntry e : entries) {
            items.put(new JSONObject(e.entryJson()));
        }
        JSONObject response = dataSource.postImportValidate(new JSONObject().put("items", items));
        JSONArray results = response.optJSONArray("results");
        List<ImportVerdict> verdicts = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            JSONObject r = results != null ? results.optJSONObject(i) : null;
            if (r == null) {
                verdicts.add(new ImportVerdict(ImportVerdict.STATUS_NO_APP, null, null));
                continue;
            }
            verdicts.add(new ImportVerdict(
                    r.optString("status", ImportVerdict.STATUS_NO_APP),
                    r.isNull("verdict") ? null : r.optString("verdict", null),
                    r.isNull("idKind") ? null : r.optString("idKind", null)));
        }
        return verdicts;
    }

    @Override
    public void importItem(String app, String entryJson, String payloadBoxPath) throws Exception {
        JSONObject body = new JSONObject()
                .put("app", app)
                .put("entry", new JSONObject(entryJson))
                .put("payloadPath", payloadBoxPath);
        JSONObject result = dataSource.postImportItem(body);
        if (!result.optBoolean("ok", false)) {
            throw new Exception(result.optString("reason", result.optString("error", "import failed")));
        }
    }

    @Override
    public void delete(ItemRef ref) throws Exception {
        JSONObject result = dataSource.deleteContent(ref.app(), ref.localRef());
        if (!result.optBoolean("ok", false)) {
            throw new Exception(result.optString("reason", result.optString("error", "delete failed")));
        }
    }
}
