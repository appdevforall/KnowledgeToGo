/*
 * ============================================================================
 * Name        : DeviceIdStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-560. Random ID of this app install, bound into the box.
 * ============================================================================
 */
package org.appdevforall.k2go.boxidentity.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The random ID of this app install (K2GO-549). The box step k2go-box-identity binds its per-box
 * values to it, so a box tree that came from another phone (Clone, Backup) gets new values.
 *
 * <p>Keep the file in {@code getNoBackupFilesDir()}: Clone and Backup copy only the rootfs, so the
 * ID never travels with a tree. It is not a secret.
 */
public final class DeviceIdStore {

    /** Where PRootEngine binds the file in the guest. The box step reads this path. */
    public static final String GUEST_PATH = "/run/k2go/device-id";

    private static final String FILE_NAME = "k2go-device-id";
    private static final Pattern VALID = Pattern.compile("[0-9a-f]{32}");

    private DeviceIdStore() {
    }

    /** Returns the ID file in {@code dir}. Makes it the first time, or when it is not valid. */
    public static synchronized File ensure(File dir) throws IOException {
        File file = new File(dir, FILE_NAME);
        if (file.isFile() && VALID.matcher(read(file)).matches()) {
            return file;
        }
        byte[] id = new byte[16];
        new SecureRandom().nextBytes(id);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : id) {
            hex.append(String.format("%02x", b));
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(hex.toString().getBytes(StandardCharsets.US_ASCII));
            out.getFD().sync();
        }
        // Only an invalid file is here. Delete it: renameTo does not replace on every platform.
        if (!file.delete() && file.exists() || !tmp.renameTo(file)) {
            throw new IOException("cannot write " + file);
        }
        return file;
    }

    private static String read(File file) {
        byte[] buf = new byte[64];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }
}
