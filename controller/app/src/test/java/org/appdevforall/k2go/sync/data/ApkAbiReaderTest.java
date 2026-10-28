package org.appdevforall.k2go.sync.data;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The ABIs come from the APK's lib/<abi>/ folders only. Pure JVM. */
public class ApkAbiReaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String apk(String... entries) throws IOException {
        File f = tmp.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(f))) {
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e));
                zip.closeEntry();
            }
        }
        return f.getPath();
    }

    @Test
    public void universalApkListsEveryAbiOnce() throws IOException {
        String path = apk("classes.dex",
                "lib/arm64-v8a/libproot.so", "lib/arm64-v8a/libtermux.so",
                "lib/armeabi-v7a/libproot.so", "lib/x86/libproot.so", "lib/x86_64/libproot.so");
        assertEquals(new LinkedHashSet<>(Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64")),
                ApkAbiReader.abis(path));
    }

    @Test
    public void singleAbiSplit() throws IOException {
        assertEquals(Collections.singleton("arm64-v8a"),
                ApkAbiReader.abis(apk("AndroidManifest.xml", "lib/arm64-v8a/libproot.so")));
    }

    @Test
    public void baseWithoutNativeLibsIsEmpty() throws IOException {
        // A Play config-split base.apk: native libs live in another split.
        assertEquals(Collections.emptySet(),
                ApkAbiReader.abis(apk("AndroidManifest.xml", "classes.dex", "assets/lib/x.so", "lib/")));
    }

    @Test(expected = IOException.class)
    public void unreadableFileThrows() throws IOException {
        ApkAbiReader.abis(new File(tmp.getRoot(), "missing.apk").getPath());
    }
}
