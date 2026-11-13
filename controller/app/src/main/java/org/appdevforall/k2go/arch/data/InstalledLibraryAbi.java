/*
 * ============================================================================
 * Name        : InstalledLibraryAbi.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-571. Reads the ABI of the installed library (rootfs) from its
 *               own tree. Plain java.io, so it is unit-tested on a temp dir.
 * ============================================================================
 */
package org.appdevforall.k2go.arch.data;

import org.appdevforall.k2go.arch.domain.LibraryArch;
import org.appdevforall.k2go.deploy.domain.ElfClass;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ELF class of one of the tree's programs is the direct fact, so it comes first. The identity file
 * (docs/ROOTFS_MANIFEST.md, {@code .k2go-rootfs.json} or the older {@code .iiab-rootfs.json}) is the
 * fallback: a backup made by an older app on a 64-bit app with a 32-bit library labels it arm64, and
 * a restore keeps that label in the tree. Read on each call: a restore or a Clone that replaces the
 * tree is seen at once, with no cache to clear.
 */
public final class InstalledLibraryAbi {

    private static final String[] IDENTITY_FILES = {".k2go-rootfs.json", ".iiab-rootfs.json"};
    private static final String[] PROBE_BINARIES = {"usr/bin/env", "usr/bin/dpkg", "usr/bin/ls"};
    private static final int MAX_IDENTITY_BYTES = 64 * 1024;
    private static final Pattern ARCH = Pattern.compile("\"arch\"\\s*:\\s*\"([^\"]*)\"");

    private InstalledLibraryAbi() {}

    /**
     * @param iiabRoot the installed tree ({@code installed-rootfs/iiab}).
     * @return {@link LibraryArch#ARM64} or {@link LibraryArch#ARM32}, or {@code null} when no library
     *         is installed or its ABI cannot be told.
     */
    public static String read(File iiabRoot) {
        if (iiabRoot == null || !iiabRoot.isDirectory()) return null;
        for (String rel : PROBE_BINARIES) {
            String abi = LibraryArch.abiOf(ElfClass.of(head(new File(iiabRoot, rel), 5)));
            if (abi != null) return abi;
        }
        for (String name : IDENTITY_FILES) {
            String abi = fromIdentity(new File(iiabRoot, name));
            if (abi != null) return abi;
        }
        return null;
    }

    private static String fromIdentity(File f) {
        byte[] bytes = head(f, MAX_IDENTITY_BYTES);
        if (bytes == null) return null;
        Matcher m = ARCH.matcher(new String(bytes, StandardCharsets.UTF_8));
        if (!m.find()) return null;
        String abi = m.group(1);
        return LibraryArch.bitsOf(abi) == 0 ? null : abi;
    }

    /** Up to {@code n} leading bytes of {@code f}, or {@code null} when it cannot be read. */
    private static byte[] head(File f, int n) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[n];
            int got = 0, r;
            while (got < n && (r = in.read(buf, got, n - got)) != -1) got += r;
            if (got == n) return buf;
            byte[] out = new byte[got];
            System.arraycopy(buf, 0, out, 0, got);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
