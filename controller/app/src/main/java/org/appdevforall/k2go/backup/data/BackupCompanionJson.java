/*
 * ============================================================================
 * Name        : BackupCompanionJson.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-570. Writes and reads X.tar.json. A file that does not parse, has
 *               another format or misses a field reads as null, so the restore takes the
 *               full path (fail closed).
 * ============================================================================
 */
package org.appdevforall.k2go.backup.data;

import org.appdevforall.k2go.backup.domain.BackupCompanion;
import org.json.JSONException;
import org.json.JSONObject;

public final class BackupCompanionJson {

    /** Longest X.tar.json the restore reads. */
    public static final int MAX_BYTES = 16 * 1024;

    private BackupCompanionJson() {}

    public static String write(BackupCompanion c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("format", BackupCompanion.FORMAT);
        o.put("created", c.created);
        o.put("app_version", c.appVersion);
        o.put("abi", c.abi);
        if (c.tier != null) o.put("tier", c.tier);
        o.put("gz_bytes", c.gzBytes);
        o.put("gz_sha256", c.gzSha256);
        o.put("tar_bytes", c.tarBytes);
        return o.toString(2) + "\n";
    }

    /** @return the parsed file, or {@code null} when it is not a complete {@link BackupCompanion#FORMAT}. */
    public static BackupCompanion read(String json) {
        if (json == null || json.length() > MAX_BYTES) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (!BackupCompanion.FORMAT.equals(o.optString("format"))) return null;
            BackupCompanion c = new BackupCompanion(
                    o.optString("created", null),
                    o.optString("app_version", null),
                    o.optString("abi", null),
                    o.has("tier") ? o.optString("tier", null) : null,
                    o.optLong("gz_bytes", -1L),
                    o.optString("gz_sha256", null),
                    o.optLong("tar_bytes", -1L));
            return c.isComplete() ? c : null;
        } catch (JSONException e) {
            return null;
        }
    }
}
