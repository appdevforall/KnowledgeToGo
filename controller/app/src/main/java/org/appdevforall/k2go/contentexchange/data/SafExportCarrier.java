/*
 * ============================================================================
 * Name        : SafExportCarrier.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. SAF-backed export destination: writes the bundle to a user-picked
 *               drive/folder tree (USB-OTG or SD) through the framework Storage Access
 *               Framework. Uses DocumentsContract + ContentResolver directly (no
 *               androidx.documentfile dependency) and adds NO storage permission: the
 *               persisted tree grant from the picker is the only authority. Reaches a USB-OTG
 *               volume and an SD card uniformly and stays valid on the Play flavor, which
 *               drops MANAGE_EXTERNAL_STORAGE.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.appdevforall.k2go.contentexchange.domain.ExportCarrier;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * One SAF document tree as an {@link ExportCarrier}. The tree Uri comes from
 * {@code ACTION_OPEN_DOCUMENT_TREE} and must already carry a persisted permission.
 *
 * <p>The bundle is laid out human-navigable: each item's archive goes in a per-app subfolder
 * ({@code kiwix/}, {@code kolibri/}, {@code calibre/}) with {@code manifest.json} at the root. A write
 * overwrites any existing file of the same name (manifest or item), so a re-export leaves exactly one
 * of each, never a {@code manifest (1).json} / {@code name (1).tar} copy that SAF would make on a clash.
 */
public final class SafExportCarrier implements ExportCarrier {

    private static final String MANIFEST_NAME = "manifest.json";
    private static final String MIME_JSON = "application/json";
    private static final String MIME_BINARY = "application/octet-stream";
    private static final String MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR;

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final Uri treeDocUri;

    /** app subfolder name -> its document Uri, created/looked-up once per run. */
    private final Map<String, Uri> subfolders = new HashMap<>();

    public SafExportCarrier(Context context, Uri treeUri) {
        this.resolver = context.getContentResolver();
        this.treeUri = treeUri;
        this.treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    @Override
    public String destinationLabel() {
        String name = queryDisplayName(treeDocUri);
        return name != null ? name : treeDocUri.getLastPathSegment();
    }

    @Override
    public PayloadSink openPayload(String subfolder, String desiredName) throws IOException {
        boolean nested = subfolder != null && !subfolder.isEmpty();
        Uri parent = nested ? ensureSubfolder(subfolder) : treeDocUri;
        deleteExisting(parent, desiredName);   // overwrite, so a retry leaves no "name (1)" copy
        Uri file = createDocument(parent, MIME_BINARY, desiredName);
        OutputStream out = openOutput(file);
        // SAF may still adjust the name; record the stored name (relative to the bundle root) so the
        // manifest payload points at the real file.
        String actual = queryDisplayName(file);
        String stored = actual != null ? actual : desiredName;
        return new PayloadSink(out, nested ? subfolder + "/" + stored : stored);
    }

    @Override
    public OutputStream openManifest() throws IOException {
        deleteExisting(treeDocUri, MANIFEST_NAME);   // one authoritative manifest, never "manifest (1)"
        return openOutput(createDocument(treeDocUri, MIME_JSON, MANIFEST_NAME));
    }

    /** Create the per-app subfolder once (reuse it if it is already on the drive). */
    private Uri ensureSubfolder(String name) throws IOException {
        Uri cached = subfolders.get(name);
        if (cached != null) {
            return cached;
        }
        Uri existing = findChild(treeDocUri, name, true);
        Uri dir = existing != null ? existing : createDocument(treeDocUri, MIME_DIR, name);
        subfolders.put(name, dir);
        return dir;
    }

    /** Delete a child of {@code parent} whose display name matches, so the next create does not clash. */
    private void deleteExisting(Uri parent, String name) {
        Uri child = findChild(parent, name, false);
        if (child != null) {
            try {
                DocumentsContract.deleteDocument(resolver, child);
            } catch (Exception ignored) {
                // best-effort: if the delete fails, createDocument may fall back to a renamed copy
            }
        }
    }

    /** The document Uri of a child of {@code parent} with the given display name, or null. */
    private Uri findChild(Uri parent, String name, boolean wantDir) {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        };
        try (Cursor c = resolver.query(childrenUri, columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String childName = c.getString(1);
                if (!name.equals(childName)) {
                    continue;
                }
                boolean isDir = MIME_DIR.equals(c.getString(2));
                if (wantDir == isDir) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                }
            }
        } catch (Exception ignored) {
            // fall through to null
        }
        return null;
    }

    private Uri createDocument(Uri parent, String mime, String name) throws IOException {
        try {
            Uri uri = DocumentsContract.createDocument(resolver, parent, mime, name);
            if (uri == null) {
                throw new IOException("Could not create " + name + " on the destination");
            }
            return uri;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // Includes a SecurityException if the grant was revoked, or a provider failure.
            throw new IOException("Could not create " + name + ": " + e.getMessage(), e);
        }
    }

    private OutputStream openOutput(Uri file) throws IOException {
        try {
            OutputStream out = resolver.openOutputStream(file);
            if (out == null) {
                throw new IOException("Destination refused a write stream");
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not open the destination for writing: " + e.getMessage(), e);
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
