/*
 * ============================================================================
 * Name        : ConexCoreImportSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Data-layer implementation of the import source port against conex-core
 *               (through dash-node's /v1 relay): POST /v1/import/validate (per-item verdict),
 *               POST /v1/import/item (ingest one staged item), and DELETE /v1/content (reclaim).
 *               The manifest entries are passed to the core unchanged; the core owns the shape.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ImportSource;
import org.appdevforall.k2go.contentexchange.domain.ImportStatus;
import org.appdevforall.k2go.contentexchange.domain.ImportVerdict;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ConexCoreImportSource implements ImportSource {

    private final ConexCoreDataSource dataSource;

    public ConexCoreImportSource(ConexCoreDataSource dataSource) {
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
    public String startImport(String app, String entryJson, String payloadBoxPath) throws Exception {
        JSONObject body = new JSONObject()
                .put("app", app)
                .put("entry", new JSONObject(entryJson))
                .put("payloadPath", payloadBoxPath);
        JSONObject r = dataSource.postImportStart(body);
        String opId = r.optString("opId", "");
        if (opId.isEmpty()) {
            throw new Exception("import start returned no opId");
        }
        return opId;
    }

    @Override
    public ImportStatus importStatus(String opId) throws Exception {
        JSONObject r;
        try {
            r = dataSource.getImportStatus(opId);
        } catch (ConexCoreDataSource.ConexHttpException e) {
            if (e.code() == 404) {
                return ImportStatus.gone();   // the op was swept: the caller re-starts
            }
            throw e;
        }
        ImportStatus.State state;
        switch (r.optString("state", "")) {
            case "done":
                state = ImportStatus.State.DONE;
                break;
            case "error":
                state = ImportStatus.State.ERROR;
                break;
            default:
                state = ImportStatus.State.RUNNING;
                break;
        }
        // The core tags an out-of-space failure with code "no_space" so the run stops with a space
        // message, not a per-item retry.
        String error = r.isNull("error") ? null : r.optString("error", null);
        boolean noSpace = "no_space".equals(r.optString("code", ""));
        return new ImportStatus(state, r.optLong("movedBytes", 0L), r.optLong("totalBytes", 0L),
                error, noSpace);
    }

    @Override
    public void abortImport(String opId) {
        if (opId == null || opId.isEmpty()) {
            return;
        }
        try {
            dataSource.deleteImportOp(opId);
        } catch (Exception ignored) {
            // Best-effort: a swept or already-dropped op 404s; the box's TTL sweep is the backstop.
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
