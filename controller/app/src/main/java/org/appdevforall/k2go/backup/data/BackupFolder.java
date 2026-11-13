/*
 * ============================================================================
 * Name        : BackupFolder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-570. Owner of the persisted grant on the folder the user picked for
 *               backups. The grant lets a backup write X.tar.gz and X.tar.json, and lets a
 *               restore find one file from the other. One grant at a time: picking a new
 *               folder releases the old one. Other features that prune grants keep this one.
 * ============================================================================
 */
package org.appdevforall.k2go.backup.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

public final class BackupFolder {

    private static final String TAG = "BackupFolder";
    private static final String PREFS = "k2go_backup";
    private static final String KEY_TREE = "tree_uri";
    private static final int FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private BackupFolder() {}

    /** The backup folder the app still holds a grant for, or {@code null}. */
    @Nullable
    public static Uri get(Context ctx) {
        String s = prefs(ctx).getString(KEY_TREE, null);
        if (s == null) return null;
        Uri tree = Uri.parse(s);
        for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isWritePermission()) return tree;
        }
        return null;
    }

    /** Keep a grant on {@code tree} and release the grant on the previous backup folder. */
    public static void remember(Context ctx, Uri tree) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            cr.takePersistableUriPermission(tree, FLAGS);
        } catch (SecurityException e) {
            Log.w(TAG, "no persistable grant on the backup folder: " + e.getMessage());
            return;
        }
        String old = prefs(ctx).getString(KEY_TREE, null);
        if (old != null && !old.equals(tree.toString())) {
            try {
                cr.releasePersistableUriPermission(Uri.parse(old), FLAGS);
            } catch (Exception ignored) {
                // already gone
            }
        }
        prefs(ctx).edit().putString(KEY_TREE, tree.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
