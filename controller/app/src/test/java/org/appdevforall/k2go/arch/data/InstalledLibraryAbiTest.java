package org.appdevforall.k2go.arch.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.appdevforall.k2go.arch.domain.LibraryArch;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class InstalledLibraryAbiTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, byte[] bytes) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
    }

    private static void json(File root, String name, String arch) throws Exception {
        write(new File(root, name), ("{\"schema\":1,\"kind\":\"iiab-rootfs\",\"arch\": \"" + arch
                + "\",\"deb_arch\":\"x\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] elf(int eiClass) {
        return new byte[]{0x7F, 'E', 'L', 'F', (byte) eiClass, 1, 1, 0};
    }

    @Test
    public void noTreeMeansNoLibrary() throws Exception {
        assertNull(InstalledLibraryAbi.read(null));
        assertNull(InstalledLibraryAbi.read(new File(tmp.getRoot(), "missing")));
        assertNull(InstalledLibraryAbi.read(tmp.newFolder("empty")));
    }

    @Test
    public void aProgramWinsOverAWrongIdentityFile() throws Exception {
        // An older app backup of a 32-bit library on a 64-bit app labeled it arm64.
        File root = tmp.newFolder("iiab");
        json(root, ".k2go-rootfs.json", LibraryArch.ARM64);
        write(new File(root, "usr/bin/env"), elf(1));
        assertEquals(LibraryArch.ARM32, InstalledLibraryAbi.read(root));
    }

    @Test
    public void theIdentityFileIsUsedWhenNoProgramCanBeRead() throws Exception {
        File root = tmp.newFolder("iiab");
        json(root, ".k2go-rootfs.json", LibraryArch.ARM64);
        json(root, ".iiab-rootfs.json", LibraryArch.ARM32);
        assertEquals(LibraryArch.ARM64, InstalledLibraryAbi.read(root));
    }

    @Test
    public void theOlderIdentityFileNameStillReads() throws Exception {
        File root = tmp.newFolder("iiab");
        json(root, ".iiab-rootfs.json", LibraryArch.ARM32);
        assertEquals(LibraryArch.ARM32, InstalledLibraryAbi.read(root));
    }

    @Test
    public void anOldTreeIsJudgedByAProgram() throws Exception {
        File root = tmp.newFolder("iiab");
        write(new File(root, "usr/bin/env"), elf(1));
        assertEquals(LibraryArch.ARM32, InstalledLibraryAbi.read(root));
    }

    @Test
    public void anUnknownArchInTheFileMeansUnknown() throws Exception {
        File root = tmp.newFolder("iiab");
        json(root, ".k2go-rootfs.json", "x86_64");
        assertNull(InstalledLibraryAbi.read(root));
    }

    @Test
    public void noFileAndNoElfMeansUnknown() throws Exception {
        File root = tmp.newFolder("iiab");
        write(new File(root, "usr/bin/env"), "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
        assertNull(InstalledLibraryAbi.read(root));
    }
}
