package org.appdevforall.k2go.download.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * K2GO-554 spike, Step B: run the same {@link MinisignVerifier} on-device, inside K2Go, on
 * the real Android runtime (ART, the app's dependency graph, BouncyCastle on device). Vectors
 * are produced by minisign on the host and bundled as androidTest assets.
 */
@RunWith(AndroidJUnit4.class)
public class MinisignVerifierInstrumentedTest {

    private Context testCtx() {
        return InstrumentationRegistry.getInstrumentation().getContext();
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = testCtx().getAssets().open(name);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                bos.write(b, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private File assetToFile(String name) throws IOException {
        File f = File.createTempFile("minisign", ".bin");
        try (InputStream in = testCtx().getAssets().open(name);
             OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
        }
        return f;
    }

    @Test
    public void verifiesOnDevice() throws Exception {
        MinisignVerifier.PublicKey pub = MinisignVerifier.PublicKey.parse(readAsset("minisign/spike.pub"));
        File bin = assetToFile("minisign/test.bin");
        try {
            assertTrue("valid minisign signature must verify on device",
                    MinisignVerifier.verify(bin, readAsset("minisign/test.bin.minisig"), pub));
        } finally {
            bin.delete();
        }
    }

    @Test
    public void rejectsTamperedOnDevice() throws Exception {
        MinisignVerifier.PublicKey pub = MinisignVerifier.PublicKey.parse(readAsset("minisign/spike.pub"));
        File bin = assetToFile("minisign/test.bin");
        try {
            byte[] data;
            try (InputStream in = new FileInputStream(bin);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) {
                    bos.write(b, 0, n);
                }
                data = bos.toByteArray();
            }
            data[0] ^= 0x01;
            try (OutputStream out = new FileOutputStream(bin)) {
                out.write(data);
            }
            assertFalse("a tampered file must not verify on device",
                    MinisignVerifier.verify(bin, readAsset("minisign/test.bin.minisig"), pub));
        } finally {
            bin.delete();
        }
    }
}
