/*
 * ============================================================================
 * Name        : ImportManifestReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Reads a bundle's items from the drive: parse the top-level manifest.json
 *               into ImportEntry objects, keeping each raw entry verbatim for the core; and, when no
 *               manifest is present (an export interrupted before it wrote the index), scan the folder
 *               for recognizable files (a Kiwix .zim) and synthesize entries from the filename. Pure
 *               JSON (org.json), no HTTP and no Android: the parse / scan are unit-testable on a JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ImportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ImportManifest;
import org.appdevforall.k2go.contentexchange.domain.ImportScan;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ImportManifestReader {

    private static final String MANIFEST_NAME = "manifest.json";

    /** Read a bundle through the carrier: a real manifest when present, else a folder scan. */
    public ImportManifest read(ImportCarrier carrier) throws Exception {
        byte[] manifest = carrier.readManifest();
        if (manifest != null && manifest.length > 0) {
            List<ImportEntry> entries = parse(manifest);
            if (!entries.isEmpty()) {
                return new ImportManifest(entries, true, new ArrayList<>());
            }
            // A manifest that parsed to nothing usable: fall through to a scan rather than show empty.
        }
        return scan(carrier.listFiles());
    }

    /** Parse {@code manifest.json} bytes into entries, keeping each raw entry for the core. */
    public static List<ImportEntry> parse(byte[] manifestBytes) throws Exception {
        List<ImportEntry> out = new ArrayList<>();
        if (manifestBytes == null || manifestBytes.length == 0) {
            return out;
        }
        JSONObject root = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        JSONArray items = root.optJSONArray("items");
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject entry = items.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String app = entry.optString("app", "");
            JSONObject identity = entry.optJSONObject("identity");
            String localRef = identity != null ? identity.optString("localRef", "") : "";
            String payload = entry.optString("payload", "");
            // An item we cannot address or whose archive the manifest does not name is not importable.
            if (app.isEmpty() || localRef.isEmpty() || payload.isEmpty()) {
                continue;
            }
            out.add(new ImportEntry(app, localRef, entry.optString("title", localRef),
                    entry.optLong("bytes", 0L), payload, entry.toString()));
        }
        return out;
    }

    /** Scan a manifest-less folder: recognize what each file belongs to by its name alone. A v2 bundle
     *  file arrives as a relative path ("kiwix/foo.zim"): recognize and key on its basename, but keep
     *  the full relative path as the payload so the carrier can open it. */
    public static ImportManifest scan(List<ImportCarrier.DriveFile> files) throws Exception {
        List<ImportEntry> entries = new ArrayList<>();
        List<String> unrecognized = new ArrayList<>();
        if (files != null) {
            for (ImportCarrier.DriveFile file : files) {
                String payload = file.name();
                if (payload == null) {
                    continue;
                }
                String base = basename(payload);
                if (MANIFEST_NAME.equalsIgnoreCase(base)) {
                    continue;
                }
                String app = ImportScan.recognizeApp(base);
                if (ImportScan.APP_KIWIX.equals(app)) {
                    entries.add(kiwixEntry(payload, base, file.size()));
                } else {
                    unrecognized.add(payload);
                }
            }
        }
        return new ImportManifest(entries, false, unrecognized);
    }

    /** The file name after the last path separator (the item's own handle), for a flat or nested name. */
    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Synthesize a Kiwix entry from a .zim basename: dedup keys from the name, no UUID (soft). The
     *  payload is the drive-relative path, the identity localRef is the basename. */
    private static ImportEntry kiwixEntry(String payload, String base, long size) throws Exception {
        String[] p = ImportScan.parseZimFilename(base);
        String stem = base.toLowerCase(java.util.Locale.US).endsWith(".zim")
                ? base.substring(0, base.length() - 4) : base;
        JSONObject line = new JSONObject()
                .put("creator", p[0]).put("lang", p[1]).put("flavour", p[2]);
        JSONObject dedup = new JSONObject()
                .put("line", line).put("build", JSONObject.NULL)
                .put("version", p[3]).put("idKind", "SOFT");
        JSONObject identity = new JSONObject().put("localRef", base).put("dedup", dedup);
        JSONObject entry = new JSONObject()
                .put("app", ImportScan.APP_KIWIX).put("kind", "zim")
                .put("title", stem).put("bytes", size)
                .put("identity", identity).put("payload", payload);
        return new ImportEntry(ImportScan.APP_KIWIX, base, stem, size, payload, entry.toString());
    }
}
