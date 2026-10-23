/*
 * ============================================================================
 * Name        : SafImportCarrier.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. SAF-backed import source: reads a bundle (manifest.json + one archive per
 *               item) from a user-picked drive/folder tree through the framework Storage Access
 *               Framework. The mirror of SafExportCarrier (the write side): DocumentsContract +
 *               ContentResolver directly (no androidx.documentfile), and NO storage permission: the
 *               persisted tree grant from the picker is the only authority. Reaches a USB-OTG volume
 *               and an SD card uniformly and stays valid on the Play flavor.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.appdevforall.k2go.contentexchange.domain.ImportCarrier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One SAF document tree as an {@link ImportCarrier}. The tree Uri comes from
 * {@code ACTION_OPEN_DOCUMENT_TREE} and must already carry a persisted permission. Children are listed
 * once and indexed by display name, so the manifest read and each payload open resolve without a
 * re-scan.
 */
public final class SafImportCarrier implements ImportCarrier {

    private static final String MANIFEST_NAME = "manifest.json";
    /** A manifest larger than this is treated as absent: the real index is a few KB. */
    private static final long MANIFEST_MAX_BYTES = 8L * 1024 * 1024;
    private static final String MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    /** The per-app subfolders a v2 bundle lays items in; walked one level for the no-manifest scan. */
    private static final Set<String> APP_SUBFOLDERS = new HashSet<>(
            Arrays.asList("kiwix", "kolibri", "calibre"));

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final Uri treeDocUri;

    /** display name -> document id, built lazily from one children query. */
    private Map<String, String> docIds;
    private List<DriveFile> files;

    public SafImportCarrier(Context context, Uri treeUri) {
        this.resolver = context.getContentResolver();
        this.treeUri = treeUri;
        this.treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    @Override
    public String sourceLabel() {
        String name = queryDisplayName(treeDocUri);
        return name != null ? name : treeUri.getLastPathSegment();
    }

    @Override
    public byte[] readManifest() throws IOException {
        index();
        String id = docIds.get(MANIFEST_NAME);
        if (id == null) {
            // Case-insensitive fallback, for a drive that preserved odd casing.
            for (Map.Entry<String, String> e : docIds.entrySet()) {
                if (MANIFEST_NAME.equalsIgnoreCase(e.getKey())) {
                    id = e.getValue();
                    break;
                }
            }
        }
        if (id == null) {
            return null;
        }
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
        try (InputStream in = openStream(docUri)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            long total = 0L;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MANIFEST_MAX_BYTES) {
                    return null;   // too large to be the index: treat the bundle as manifest-less
                }
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    @Override
    public List<DriveFile> listFiles() throws IOException {
        index();
        return new ArrayList<>(files);
    }

    @Override
    public InputStream openPayload(String name) throws IOException {
        index();
        String id = docIds.get(name);
        if (id == null) {
            // A v2 payload path ("app/file") whose folder the index did not pre-walk: walk it directly.
            int slash = name.indexOf('/');
            if (slash > 0) {
                id = resolveNestedId(name.substring(0, slash), name.substring(slash + 1));
            }
        }
        if (id == null) {
            throw new IOException("File not found on the drive: " + name);
        }
        return openStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
    }

    /**
     * Index the bundle once: files directly in the chosen folder by bare name (a flat pre-v2 bundle),
     * plus files one level down in each per-app subfolder by relative path ("app/file", a v2 bundle).
     * Both forms land in the same map, so {@code openPayload} and the no-manifest scan resolve either.
     */
    private void index() throws IOException {
        if (docIds != null) {
            return;
        }
        Map<String, String> ids = new LinkedHashMap<>();
        List<DriveFile> found = new ArrayList<>();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        for (String[] row : listChildren(rootId)) {
            String id = row[0];
            String name = row[1];
            if (name == null) {
                continue;
            }
            if (MIME_DIR.equals(row[3])) {
                // Walk one level into a known app subfolder; index its files by "app/file".
                if (APP_SUBFOLDERS.contains(name.toLowerCase(Locale.US))) {
                    for (String[] sub : listChildren(id)) {
                        String subName = sub[1];
                        if (subName == null || MIME_DIR.equals(sub[3])) {
                            continue;
                        }
                        String rel = name + "/" + subName;
                        ids.put(rel, sub[0]);
                        found.add(new DriveFile(rel, parseSize(sub[2])));
                    }
                }
                continue;
            }
            ids.put(name, id);
            found.add(new DriveFile(name, parseSize(row[2])));
        }
        this.docIds = ids;
        this.files = found;
    }

    /** Resolve "dir/child" by walking into the named subfolder (one level), for a folder the index did
     *  not pre-walk. Returns the child's document id, or null. */
    private String resolveNestedId(String dir, String child) throws IOException {
        String dirId = null;
        for (String[] row : listChildren(DocumentsContract.getTreeDocumentId(treeUri))) {
            if (dir.equals(row[1]) && MIME_DIR.equals(row[3])) {
                dirId = row[0];
                break;
            }
        }
        if (dirId == null) {
            return null;
        }
        for (String[] row : listChildren(dirId)) {
            if (child.equals(row[1]) && !MIME_DIR.equals(row[3])) {
                return row[0];
            }
        }
        return null;
    }

    /** One children query, each row as {docId, displayName, size, mimeType}. */
    private List<String[]> listChildren(String parentDocId) throws IOException {
        List<String[]> rows = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        };
        try (Cursor c = resolver.query(childrenUri, columns, null, null, null)) {
            if (c == null) {
                throw new IOException("Could not read the selected folder");
            }
            while (c.moveToNext()) {
                rows.add(new String[]{
                        c.getString(0), c.getString(1),
                        c.isNull(2) ? "0" : Long.toString(c.getLong(2)), c.getString(3)});
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read the selected folder: " + e.getMessage(), e);
        }
        return rows;
    }

    private static long parseSize(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private InputStream openStream(Uri docUri) throws IOException {
        try {
            InputStream in = resolver.openInputStream(docUri);
            if (in == null) {
                throw new IOException("Could not open a file on the drive for reading");
            }
            return in;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not open a file on the drive: " + e.getMessage(), e);
        }
    }

    private String queryDisplayName(Uri docUri) {
        try (Cursor c = resolver.query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
            // fall through to null
        }
        return null;
    }
}
