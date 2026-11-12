package org.appdevforall.k2go.download.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.util.encoders.Base64;
import org.junit.Test;

/**
 * JVM unit tests for the minisign verifier and the pinned-key trust decision (K2GO-554).
 *
 * <p>Vectors are generated in the test with BouncyCastle: an Ed25519 key and a prehashed ("ED")
 * minisign signature built exactly as minisign writes one. The test is therefore hermetic and
 * runs in CI with no external files.
 */
public class MinisignVerifierTest {

    /** A throwaway Ed25519 key that can emit a minisign .pub line and sign a file like minisign. */
    private static final class Key {
        final byte[] keyId;
        final byte[] priv = new byte[Ed25519.SECRET_KEY_SIZE];
        final byte[] pub = new byte[Ed25519.PUBLIC_KEY_SIZE];

        Key() {
            this(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        }

        Key(byte[] keyId) {
            this.keyId = keyId;
            Ed25519.generatePrivateKey(new SecureRandom(), priv);
            Ed25519.generatePublicKey(priv, 0, pub, 0);
        }

        /** The base64 public-key line: "Ed"(2) + keyId(8) + Ed25519 public key(32). */
        String pubLine() {
            byte[] blob = new byte[42];
            blob[0] = 'E';
            blob[1] = 'd';
            System.arraycopy(keyId, 0, blob, 2, 8);
            System.arraycopy(pub, 0, blob, 10, 32);
            return b64(blob);
        }

        /** A prehashed minisign signature over {@code file}, as the four-line .minisig text. */
        String sign(File file, String trustedComment) throws Exception {
            byte[] digest = blake2b512(file);
            byte[] sig = new byte[Ed25519.SIGNATURE_SIZE];
            Ed25519.sign(priv, 0, digest, 0, digest.length, sig, 0);

            byte[] blob = new byte[74];           // sigAlg(2) + keyId(8) + signature(64)
            blob[0] = 'E';
            blob[1] = 'D';                         // "ED" = prehashed BLAKE2b
            System.arraycopy(keyId, 0, blob, 2, 8);
            System.arraycopy(sig, 0, blob, 10, 64);

            byte[] tc = trustedComment.getBytes(StandardCharsets.UTF_8);
            byte[] globalMsg = new byte[sig.length + tc.length];
            System.arraycopy(sig, 0, globalMsg, 0, sig.length);
            System.arraycopy(tc, 0, globalMsg, sig.length, tc.length);
            byte[] globalSig = new byte[Ed25519.SIGNATURE_SIZE];
            Ed25519.sign(priv, 0, globalMsg, 0, globalMsg.length, globalSig, 0);

            return "untrusted comment: test\n"
                    + b64(blob) + "\n"
                    + "trusted comment: " + trustedComment + "\n"
                    + b64(globalSig) + "\n";
        }
    }

    private static String b64(byte[] b) {
        return new String(Base64.encode(b), StandardCharsets.US_ASCII);
    }

    private static byte[] blake2b512(File file) throws Exception {
        Blake2bDigest d = new Blake2bDigest(512);
        byte[] data = Files.readAllBytes(file.toPath());
        d.update(data, 0, data.length);
        byte[] out = new byte[64];
        d.doFinal(out, 0);
        return out;
    }

    private static File tmp(String content) throws Exception {
        File f = File.createTempFile("mvtest", ".bin");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // ---- the signature primitive -------------------------------------------------

    @Test
    public void verifiesValidSignature() throws Exception {
        Key k = new Key();
        File f = tmp("hello rootfs");
        String sig = k.sign(f, "built 2026.315");
        assertTrue(MinisignVerifier.verify(f, sig, MinisignVerifier.PublicKey.parse(k.pubLine())));
    }

    @Test
    public void rejectsTamperedFile() throws Exception {
        Key k = new Key();
        File f = tmp("hello rootfs");
        String sig = k.sign(f, "c");
        Files.write(f.toPath(), "hxllo rootfs".getBytes(StandardCharsets.UTF_8));
        assertFalse(MinisignVerifier.verify(f, sig, MinisignVerifier.PublicKey.parse(k.pubLine())));
    }

    @Test
    public void rejectsWrongKey() throws Exception {
        Key signer = new Key();
        Key other = new Key();
        File f = tmp("data");
        String sig = signer.sign(f, "c");
        assertFalse(MinisignVerifier.verify(f, sig, MinisignVerifier.PublicKey.parse(other.pubLine())));
    }

    @Test
    public void rejectsLegacyNonPrehashedTag() throws Exception {
        // minisign's legacy "Ed" form signs the whole file, not its BLAKE2b digest, and cannot be
        // verified by streaming. The verifier must accept only the prehashed "ED" form.
        Key k = new Key();
        File f = tmp("data");
        String[] lines = k.sign(f, "c").split("\n");
        byte[] blob = Base64.decode(lines[1].trim());
        blob[1] = 'd';                            // "ED" -> "Ed"
        lines[1] = b64(blob);
        String legacy = String.join("\n", lines) + "\n";
        assertFalse(MinisignVerifier.verify(f, legacy, MinisignVerifier.PublicKey.parse(k.pubLine())));
    }

    // ---- the pinned-key trust decision -------------------------------------------

    @Test
    public void trustedWhenKeyPinned() throws Exception {
        Key k = new Key();
        TrustedSigningKeys keys = TrustedSigningKeys.of(k.pubLine());
        File f = tmp("x");
        assertTrue(MinisignVerifier.isTrusted(f, k.sign(f, "c"), keys));
    }

    @Test
    public void notTrustedWhenKeyIdUnknown() throws Exception {
        Key signer = new Key(new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
        Key pinned = new Key(new byte[] {1, 1, 1, 1, 1, 1, 1, 1});
        TrustedSigningKeys keys = TrustedSigningKeys.of(pinned.pubLine());
        File f = tmp("x");
        // a perfectly valid signature, but from a key id we do not pin -> fail closed
        assertFalse(MinisignVerifier.isTrusted(f, signer.sign(f, "c"), keys));
    }

    @Test
    public void notTrustedOnMalformedOrMissingSignature() throws Exception {
        TrustedSigningKeys keys = TrustedSigningKeys.of(new Key().pubLine());
        File f = tmp("x");
        assertFalse(MinisignVerifier.isTrusted(f, "not a minisig", keys));
        assertFalse(MinisignVerifier.isTrusted(f, null, keys));
    }

    @Test
    public void productionPinsTheCommittedKey() {
        // Guards against a typo in the hard-coded pinned key: the production set must contain the
        // key id of the committed controller/rootfs-signing.pub.
        MinisignVerifier.PublicKey committed = MinisignVerifier.PublicKey.parse(
                "RWRgyYEHJVvkxSinsxrA+/xOfzno8cPOFk9GFruR3w54RLFUVHDaH9Vb");
        assertNotNull("production must pin the committed key id",
                TrustedSigningKeys.PRODUCTION.forKeyId(committed.keyId));
    }
}
