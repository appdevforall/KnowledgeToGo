/*
 * ============================================================================
 * Name        : SchoolTrustFiles.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the trust files of this phone, in no_backup.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import org.appdevforall.k2go.school.trust.domain.Base64Url;
import org.appdevforall.k2go.school.trust.domain.KeyVault;
import org.appdevforall.k2go.school.trust.domain.SchoolTrustRepository;
import org.appdevforall.k2go.school.trust.domain.TrustRecords;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

/**
 * Two files, both in no_backup (Backup, Clone and Android backup never copy them):
 * <ul>
 * <li>{@code school/root.token}: the pinned root. PRootEngine binds the folder into the box, so the
 * box reads the same root (phase 5).</li>
 * <li>{@code school-root/root.k2key}: the sealed school key, only on device zero. This folder is NOT
 * bound into the box: a command in the box or a new library cannot touch it. The file has the
 * root token too, so it is also the backup file (one format).</li>
 * </ul>
 */
public final class SchoolTrustFiles implements SchoolTrustRepository {

    static final String FORMAT = "k2go-school-key";
    static final int MAX_FILE = 16 * 1024;

    private final File rootToken;
    private final File keyFile;

    /** {@code noBackupDir}: Context.getNoBackupFilesDir(). */
    public SchoolTrustFiles(File noBackupDir) {
        rootToken = new File(new File(noBackupDir, "school"), "root.token");
        keyFile = new File(new File(noBackupDir, "school-root"), "root.k2key");
    }

    @Override
    public TrustRecords.Root root() {
        byte[] b = read(rootToken);
        return b == null ? null : TrustCodec.readRoot(new String(b, StandardCharsets.US_ASCII).trim());
    }

    @Override
    public KeyVault.Sealed vault() {
        Backup b = parseBackup(read(keyFile));
        TrustRecords.Root pinned = root();
        // The key belongs to this phone only with the root that the phone pins.
        return b != null && pinned != null && pinned.school.equals(b.root.school) ? b.vault : null;
    }

    @Override
    public String rootToken(PrivateKey key, String rootKeySpkiB64, String school, String name, long until) {
        return TrustCodec.signRoot(key, rootKeySpkiB64, school, name, until);
    }

    @Override
    public boolean saveDeviceZero(String token, KeyVault.Sealed vault) {
        try {
            JSONObject v = new JSONObject()
                    .put("kdf", "pbkdf2-sha256")
                    .put("iterations", vault.iterations)
                    .put("salt", Base64Url.encodeStandard(vault.salt))
                    .put("iv", Base64Url.encodeStandard(vault.iv))
                    .put("ct", Base64Url.encodeStandard(vault.ciphertext));
            JSONObject file = new JSONObject().put("format", FORMAT).put("v", 1).put("root", token).put("vault", v);
            // The key first: a stop between the two writes leaves a key with no pinned root, which
            // counts as no school (vault() is null), never a school with no key.
            write(keyFile, file.toString().getBytes(StandardCharsets.UTF_8));
            write(rootToken, token.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public byte[] backup() {
        return vault() == null ? null : read(keyFile);
    }

    @Override
    public Backup parseBackup(byte[] file) {
        if (file == null || file.length > MAX_FILE) return null;
        try {
            JSONObject o = new JSONObject(new String(file, StandardCharsets.UTF_8));
            if (!FORMAT.equals(o.opt("format")) || !Integer.valueOf(1).equals(o.opt("v"))) return null;
            Object token = o.opt("root");
            JSONObject v = o.optJSONObject("vault");
            if (!(token instanceof String) || v == null || !"pbkdf2-sha256".equals(v.opt("kdf"))) return null;
            Object it = v.opt("iterations");
            if (!(it instanceof Integer)) return null;
            TrustRecords.Root root = TrustCodec.readRoot((String) token);
            KeyVault.Sealed sealed = KeyVault.Sealed.of((Integer) it, Base64Url.decodeStandard(v.optString("salt", null)),
                    Base64Url.decodeStandard(v.optString("iv", null)), Base64Url.decodeStandard(v.optString("ct", null)));
            return root == null || sealed == null ? null : new Backup((String) token, root, sealed);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] read(File f) {
        if (!f.isFile() || f.length() > MAX_FILE) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in, MAX_FILE);
        } catch (IOException e) {
            return null;
        }
    }

    /** At most {@code max} bytes; null when the stream has more. */
    public static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) return null;
        }
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (f.exists() && !f.delete() || !tmp.renameTo(f)) throw new IOException("cannot write " + f);
    }
}
