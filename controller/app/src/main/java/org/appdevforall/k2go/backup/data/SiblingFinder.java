/*
 * ============================================================================
 * Name        : SiblingFinder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-569. Finds a file next to a picked document by its name, so the user
 *               picks one file of a pair (X.tar.gz with X.tar.json or X.tar.gz.minisig).
 *               A picked document grants access to that file only. The app can still read
 *               a sibling when it holds a grant on the folder, or with All-files access.
 *               Otherwise the caller asks the user to pick the second file.
 * ============================================================================
 */
package org.appdevforall.k2go.backup.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;

import org.appdevforall.k2go.permissions.StoragePermission;

import java.io.File;

public final class SiblingFinder {

    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final String DOWNLOADS = "com.android.providers.downloads.documents";

    private SiblingFinder() {}

    /** The file name of a document or file Uri, or {@code null}. */
    @Nullable
    public static String displayName(Context ctx, Uri doc) {
        if ("file".equals(doc.getScheme())) return new File(doc.getPath()).getName();
        try (Cursor c = ctx.getContentResolver().query(doc,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
            // no name
        }
        return null;
    }

    /** True when the app can read the folder of {@code picked}: a sibling it does not find is absent. */
    public static boolean canSeeSiblings(Context ctx, Uri picked) {
        return grantedTree(ctx, picked) != null || (pathAccess(ctx) && parentDir(picked) != null);
    }

    /** The document named {@code name} in the folder of {@code picked}, or {@code null}. */
    @Nullable
    public static Uri find(Context ctx, Uri picked, String name) {
        if (name == null) return null;
        Uri tree = grantedTree(ctx, picked);
        if (tree != null) {
            String id = siblingId(DocumentsContract.getDocumentId(picked), name);
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            if (name.equals(displayName(ctx, doc))) return doc;
        }
        if (pathAccess(ctx)) {
            File dir = parentDir(picked);
            File f = dir == null ? null : new File(dir, name);
            if (f != null && f.isFile()) return Uri.fromFile(f);
        }
        return null;
    }

    /** True when File paths on shared storage are readable: All-files access, and not Android 10 scoped storage. */
    private static boolean pathAccess(Context ctx) {
        return StoragePermission.isGranted(ctx)
                && (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q || Environment.isExternalStorageLegacy());
    }

    /** A persisted folder grant that covers {@code picked}, or {@code null}. */
    @Nullable
    private static Uri grantedTree(Context ctx, Uri picked) {
        if (!EXTERNAL_STORAGE.equals(picked.getAuthority())) return null;
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(picked);
        } catch (Exception e) {
            return null;
        }
        ContentResolver cr = ctx.getContentResolver();
        for (UriPermission p : cr.getPersistedUriPermissions()) {
            Uri tree = p.getUri();
            if (!p.isReadPermission() || !EXTERNAL_STORAGE.equals(tree.getAuthority())) continue;
            String treeId;
            try {
                treeId = DocumentsContract.getTreeDocumentId(tree);
            } catch (Exception e) {
                continue;
            }
            String parent = docId.substring(0, Math.max(docId.lastIndexOf('/'), docId.indexOf(':') + 1));
            if (parent.equals(treeId) || parent.startsWith(treeId + "/")) return tree;
        }
        return null;
    }

    private static String siblingId(String docId, String name) {
        int slash = docId.lastIndexOf('/');
        if (slash >= 0) return docId.substring(0, slash + 1) + name;
        return docId.substring(0, docId.indexOf(':') + 1) + name;
    }

    /** The folder of {@code picked} on the file system, when its Uri tells it. */
    @Nullable
    private static File parentDir(Uri picked) {
        try {
            if ("file".equals(picked.getScheme())) return new File(picked.getPath()).getParentFile();
            String id = DocumentsContract.getDocumentId(picked);
            if (EXTERNAL_STORAGE.equals(picked.getAuthority())) {
                int colon = id.indexOf(':');
                if (colon < 0) return null;
                String volume = id.substring(0, colon);
                File root = "primary".equals(volume)
                        ? Environment.getExternalStorageDirectory() : new File("/storage", volume);
                return new File(root, id.substring(colon + 1)).getParentFile();
            }
            if (DOWNLOADS.equals(picked.getAuthority()) && id.startsWith("raw:")) {
                return new File(id.substring(4)).getParentFile();
            }
        } catch (Exception ignored) {
            // not a path we can tell
        }
        return null;
    }
}
