package org.iiab.controller.download.domain;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** ADFA-4676: size + SHA-256 gate. */
public class DownloadVerifierTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }

    @Test public void okWhenSizeAndHashMatch() throws Exception {
        byte[] data = "hello rootfs integrity".getBytes(StandardCharsets.UTF_8);
        File f = tmp.newFile("good.bin");
        Files.write(f.toPath(), data);
        assertEquals(DownloadVerifier.Result.OK,
                DownloadVerifier.verify(f, data.length, sha256(data)));
    }

    @Test public void wrongSizeWhenTruncated() throws Exception {
        byte[] data = "hello rootfs integrity".getBytes(StandardCharsets.UTF_8);
        File f = tmp.newFile("short.bin");
        Files.write(f.toPath(), data);
        assertEquals(DownloadVerifier.Result.WRONG_SIZE,
                DownloadVerifier.verify(f, data.length + 100, sha256(data)));
    }

    @Test public void wrongHashWhenContentDiffers() throws Exception {
        byte[] data = "hello rootfs integrity".getBytes(StandardCharsets.UTF_8);
        File f = tmp.newFile("bad.bin");
        Files.write(f.toPath(), data);
        String badHash = "00" + sha256(data).substring(2);
        assertEquals(DownloadVerifier.Result.WRONG_HASH,
                DownloadVerifier.verify(f, data.length, badHash));
    }

    @Test public void missingWhenFileAbsent() throws Exception {
        File f = new File(tmp.getRoot(), "does-not-exist.bin");
        assertEquals(DownloadVerifier.Result.MISSING,
                DownloadVerifier.verify(f, 10, "abc"));
    }
}
