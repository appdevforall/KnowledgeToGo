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

/**
 * One SAF document tree as an {@link ExportCarrier}. The tree Uri comes from
 * {@code ACTION_OPEN_DOCUMENT_TREE} and must already carry a persisted permission.
 */
public final class SafExportCarrier implements ExportCarrier {

    private static final String MANIFEST_NAME = "manifest.json";
    private static final String MIME_JSON = "application/json";
    private static final String MIME_BINARY = "application/octet-stream";

    private final ContentResolver resolver;
    private final Uri treeDocUri;

    public SafExportCarrier(Context context, Uri treeUri) {
        this.resolver = context.getContentResolver();
        this.treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    @Override
    public String destinationLabel() {
        String name = queryDisplayName(treeDocUri);
        return name != null ? name : treeDocUri.getLastPathSegment();
    }

    @Override
    public PayloadSink openPayload(String desiredName) throws IOException {
        Uri file = createDocument(MIME_BINARY, desiredName);
        OutputStream out = openOutput(file);
        // SAF may rename on a collision; record the name actually stored so the manifest matches.
        String actual = queryDisplayName(file);
        return new PayloadSink(out, actual != null ? actual : desiredName);
    }

    @Override
    public OutputStream openManifest() throws IOException {
        return openOutput(createDocument(MIME_JSON, MANIFEST_NAME));
    }

    private Uri createDocument(String mime, String name) throws IOException {
        try {
            Uri uri = DocumentsContract.createDocument(resolver, treeDocUri, mime, name);
            if (uri == null) {
                throw new IOException("Could not create " + name + " on the destination");
            }
            return uri;
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
