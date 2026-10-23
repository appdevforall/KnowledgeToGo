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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            throw new IOException("File not found on the drive: " + name);
        }
        return openStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
    }

    /** List the folder's direct children once (name + size + document id). */
    private void index() throws IOException {
        if (docIds != null) {
            return;
        }
        Map<String, String> ids = new LinkedHashMap<>();
        List<DriveFile> found = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
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
                String id = c.getString(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? 0L : c.getLong(2);
                String mime = c.getString(3);
                if (name == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    continue;   // files only: sub-folders are not part of a flat bundle
                }
                ids.put(name, id);
                found.add(new DriveFile(name, size));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read the selected folder: " + e.getMessage(), e);
        }
        this.docIds = ids;
        this.files = found;
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
