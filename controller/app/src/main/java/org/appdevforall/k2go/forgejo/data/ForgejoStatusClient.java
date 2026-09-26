/*
 * ============================================================================
 * Name        : ForgejoStatusClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-422. App-side client of the dash-node Forgejo status endpoint
 *               (static/dashboard GET /k2go-api/forgejo/status, dash-node 1.3.6).
 *               Read-only: it tells the module detail whether to offer "Install
 *               repos", show the repos as present, or block (an admin exists that
 *               K2Go cannot authenticate). The box does the work over its own API,
 *               so this is a plain HTTP GET the app runs on an IO thread.
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.data;

import androidx.annotation.Nullable;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Fetches the box Forgejo status. {@link #fetch()} BLOCKS (one HTTP GET), so callers run it off the
 *  main thread. Returns {@code null} when the box did not answer (unreachable / not ready). */
public final class ForgejoStatusClient {

    /** Immutable snapshot of the box Forgejo, as the status endpoint reports it. */
    public static final class Status {
        public final boolean reachable;
        public final boolean adminExists;
        public final boolean adminAuthenticable;
        public final boolean manageable;
        public final int repoCount;

        Status(boolean reachable, boolean adminExists, boolean adminAuthenticable,
               boolean manageable, int repoCount) {
            this.reachable = reachable;
            this.adminExists = adminExists;
            this.adminAuthenticable = adminAuthenticable;
            this.manageable = manageable;
            this.repoCount = repoCount;
        }

        /** K2Go can administer the forge and it has no example repos yet -> offer "Install repos". */
        public boolean canInstallRepos() { return manageable && repoCount == 0; }

        /** The example repos are present -> nothing to install (refresh/update is K2GO-422 section A). */
        public boolean hasRepos() { return repoCount > 0; }

        /** K2Go can administer the forge AND example repos exist -> offer "Update repos". Gated on
         *  manageable so a forge we cannot authenticate (changed admin password) never offers a refresh
         *  that would 401; a blocked forge shows the blocked note instead. */
        public boolean canUpdateRepos() { return manageable && repoCount > 0; }

        /** An admin exists that K2Go cannot authenticate -> do not offer any repo action. */
        public boolean blocked() { return !manageable; }
    }

    private static final String STATUS_URL = BoxEndpoints.API + "/forgejo/status";

    /** GET the status; null on any read/parse error (treat as "cannot tell", offer nothing). */
    @Nullable
    public Status fetch() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(STATUS_URL).openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(4000);
            // The box may do a couple of short HTTP calls to the forge before answering; keep the read
            // timeout comfortably above their combined worst case so a warming forge does not make the
            // status read time out (which would silently hide the button).
            c.setReadTimeout(30000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
            if (!ok) return null;
            JSONObject j = new JSONObject(text.isEmpty() ? "{}" : text);
            JSONArray repos = j.optJSONArray("repos");
            int repoCount = repos != null ? repos.length() : 0;
            return new Status(
                    j.optBoolean("reachable", false),
                    j.optBoolean("adminExists", false),
                    j.optBoolean("adminAuthenticable", false),
                    j.optBoolean("manageable", false),
                    repoCount);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(@Nullable InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
