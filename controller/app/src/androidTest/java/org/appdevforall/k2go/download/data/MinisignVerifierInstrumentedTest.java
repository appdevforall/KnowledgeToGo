package org.appdevforall.k2go.download.data;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * K2GO-554: run the trusted-comment check on-device, inside K2Go, on the real Android runtime
 * (ART, the app's dependency graph, BouncyCastle on device). The vector is produced by minisign
 * on the host and bundled as an androidTest asset.
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

    @Test
    public void authenticatesOnDevice() throws Exception {
        TrustedSigningKeys keys = TrustedSigningKeys.of(readAsset("minisign/spike.pub"));
        assertNotNull("a minisign signature from a pinned key must authenticate on device",
                MinisignVerifier.authenticatedComment(readAsset("minisign/test.bin.minisig"), keys));
    }

    @Test
    public void rejectsEditedCommentOnDevice() throws Exception {
        TrustedSigningKeys keys = TrustedSigningKeys.of(readAsset("minisign/spike.pub"));
        String[] lines = readAsset("minisign/test.bin.minisig").replace("\r\n", "\n").split("\n");
        lines[2] = lines[2] + "x";
        assertNull("an edited trusted comment must not authenticate on device",
                MinisignVerifier.authenticatedComment(String.join("\n", lines) + "\n", keys));
    }
}
