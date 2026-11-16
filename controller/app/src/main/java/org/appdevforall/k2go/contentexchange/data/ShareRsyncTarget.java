/*
 * ============================================================================
 * Name        : ShareRsyncTarget.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-577. The rsync block of one share manifest entry ({"rsync": {port, module}}),
 *               read and validated at the network boundary. Absent or invalid means the item uses
 *               the HTTP fetch, so a sender that runs no rsync daemon still works. The manifest
 *               reader keeps the block in the entry JSON (it strips only itemRef and delivered).
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.regex.Pattern;

/** Where one shared item can be pulled with rsync: the sender's daemon port and module. */
public final class ShareRsyncTarget {

    /** Same rule as the core's module names: "<sessionId>-<itemRef>". */
    private static final Pattern MODULE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,80}$");

    private final int port;
    private final String module;

    private ShareRsyncTarget(int port, String module) {
        this.port = port;
        this.module = module;
    }

    /** The target of a manifest entry, or null when it has no valid rsync block. */
    @Nullable
    public static ShareRsyncTarget fromEntry(@Nullable String entryJson) {
        if (entryJson == null) {
            return null;
        }
        try {
            JSONObject entry = new JSONObject(entryJson);
            JSONObject rsync = entry.optJSONObject("rsync");
            if (rsync == null) {
                return null;
            }
            int port = rsync.optInt("port", 0);
            String module = rsync.optString("module", "");
            if (port < 1 || port > 65535 || !MODULE.matcher(module).matches()) {
                return null;
            }
            return new ShareRsyncTarget(port, module);
        } catch (Exception e) {
            return null;
        }
    }

    public int port() {
        return port;
    }

    public String module() {
        return module;
    }
}
