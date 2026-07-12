/*
 * ============================================================================
 * Name        : DownloadVerifier.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4676. App-side integrity gate: a file is trusted only if
 *               its byte length matches the .meta4 <size> AND its SHA-256 matches
 *               the .meta4 file-level <hash>. Independent of aria2c's exit code.
 * ============================================================================
 */
package org.iiab.controller.download.domain;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class DownloadVerifier {

    public enum Result { OK, MISSING, WRONG_SIZE, WRONG_HASH }

    private DownloadVerifier() { }

    /**
     * @param expectedSize  bytes; skipped if negative.
     * @param expectedSha256Hex lowercase/uppercase hex; if null/empty, hash step is skipped.
     */
    public static Result verify(File f, long expectedSize, String expectedSha256Hex) {
        if (f == null || !f.isFile()) return Result.MISSING;
        if (expectedSize >= 0 && f.length() != expectedSize) return Result.WRONG_SIZE;
        if (expectedSha256Hex == null || expectedSha256Hex.trim().isEmpty()) return Result.OK;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
            }
            return toHex(md.digest()).equalsIgnoreCase(expectedSha256Hex.trim())
                    ? Result.OK : Result.WRONG_HASH;
        } catch (Exception e) {
            // Cannot hash -> fail closed rather than trust an unverified file.
            return Result.WRONG_HASH;
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString().toLowerCase(Locale.US);
    }
}
