/*
 * ============================================================================
 * Name        : SecretStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Owns the on-disk rsync secrets for the Share feature: the server
 *               "rsyncd.secrets" (user:pass) and the client password file, each
 *               written owner-only. Centralizes the previously scattered writes
 *               in RsyncManager and adds clear() so secrets do not outlive a
 *               session (tech-debt S11: server secrets were never deleted on
 *               stop; client passfile leak). No android.* — a plain directory is
 *               passed in (the app uses cacheDir). Share-export, S14 step 3.
 * ============================================================================
 */
package org.iiab.controller.sync.transport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

public final class SecretStore {

    private static final String SERVER_SECRETS = "rsyncd.secrets";
    private static final String CLIENT_PASSWORD = "rsync_client.pass";

    private final File dir;

    public SecretStore(File dir) {
        this.dir = dir;
    }

    /** Writes the daemon's {@code user:pass} secrets file (owner-only). */
    public File writeServerSecrets(String user, String pass) throws IOException {
        return writeOwnerOnly(SERVER_SECRETS, user + ":" + pass);
    }

    /** Writes the client password file (owner-only). */
    public File writeClientPassword(String pass) throws IOException {
        return writeOwnerOnly(CLIENT_PASSWORD, pass);
    }

    /** Deletes the client password file (call once a transfer/dry-run finishes). */
    public void deleteClientPassword() {
        delete(CLIENT_PASSWORD);
    }

    /** S11: removes any secrets left on disk (call on stop). */
    public void clear() {
        delete(SERVER_SECRETS);
        delete(CLIENT_PASSWORD);
    }

    private void delete(String name) {
        File f = new File(dir, name);
        if (f.exists()) f.delete();
    }

    private File writeOwnerOnly(String name, String content) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f);
             PrintWriter pw = new PrintWriter(fos)) {
            pw.print(content);
            pw.flush();
        }
        // Restrict to the owner: clear all, then grant owner read/write.
        f.setExecutable(false, false);
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
        return f;
    }
}
