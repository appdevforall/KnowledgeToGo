package org.appdevforall.k2go.arch.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class KiwixLibrarySupportTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File root, String rel, String text) throws Exception {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    @Test
    public void noTreeCannotHostKiwix() throws Exception {
        assertFalse(KiwixLibrarySupport.read(null));
        assertFalse(KiwixLibrarySupport.read(new File(tmp.getRoot(), "missing")));
        assertFalse(KiwixLibrarySupport.read(tmp.newFolder("empty")));
    }

    @Test
    public void installedKiwixServeMeansYes() throws Exception {
        File root = tmp.newFolder("iiab");
        write(root, KiwixLibrarySupport.KIWIX_SERVE, "ELF");
        assertTrue(KiwixLibrarySupport.read(root));
    }

    @Test
    public void patchedRoleMeansYes() throws Exception {
        File root = tmp.newFolder("iiab");
        write(root, KiwixLibrarySupport.ROLE_DEFAULTS,
                "kiwix_arch_dict:\n  aarch64: aarch64\nkiwix_dpkg_arch_dict:\n  armhf: armv6\n");
        assertTrue(KiwixLibrarySupport.read(root));
    }

    @Test
    public void unpatchedRoleWithoutBinaryMeansNo() throws Exception {
        File root = tmp.newFolder("iiab");
        write(root, KiwixLibrarySupport.ROLE_DEFAULTS,
                "kiwix_arch_dict:\n  armv7l: armv8\n# kiwix_dpkg_arch_dict: in a comment does not count\n");
        assertFalse(KiwixLibrarySupport.read(root));
    }
}
