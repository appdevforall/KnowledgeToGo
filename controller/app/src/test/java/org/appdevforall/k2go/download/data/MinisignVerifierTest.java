package org.appdevforall.k2go.download.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.util.encoders.Base64;
import org.junit.Test;

/**
 * JVM unit tests for the minisign trusted-comment check and the pinned-key selection (K2GO-554,
 * ADR Amendment 1 A2).
 *
 * <p>Vectors are generated in the test with BouncyCastle: an Ed25519 key and a prehashed ("ED")
 * minisign signature built exactly as minisign writes one. The test is hermetic and runs in CI
 * with no external files.
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

    private static final String MANIFEST = "k2go-rootfs v1 file=x.tar.gz size=1 sha256="
            + "0000000000000000000000000000000000000000000000000000000000000000";

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

    private static String replaceLine(String minisig, int index, String line) {
        String[] lines = minisig.split("\n");
        lines[index] = line;
        return String.join("\n", lines) + "\n";
    }

    @Test
    public void authenticatesTheCommentOfAPinnedKey() throws Exception {
        Key k = new Key();
        String sig = k.sign(tmp("rootfs"), MANIFEST);
        assertEquals(MANIFEST, MinisignVerifier.authenticatedComment(sig, TrustedSigningKeys.of(k.pubLine())));
    }

    @Test
    public void rejectsACommentEditedAfterSigning() throws Exception {
        Key k = new Key();
        String sig = k.sign(tmp("rootfs"), MANIFEST);
        String edited = replaceLine(sig, 2, "trusted comment: " + MANIFEST.replace("size=1", "size=2"));
        assertNull(MinisignVerifier.authenticatedComment(edited, TrustedSigningKeys.of(k.pubLine())));
    }

    @Test
    public void rejectsAnUnknownKeyId() throws Exception {
        Key signer = new Key(new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
        Key pinned = new Key(new byte[] {1, 1, 1, 1, 1, 1, 1, 1});
        String sig = signer.sign(tmp("rootfs"), MANIFEST);
        assertNull(MinisignVerifier.authenticatedComment(sig, TrustedSigningKeys.of(pinned.pubLine())));
    }

    @Test
    public void rejectsAnotherKeyThatReusesThePinnedKeyId() throws Exception {
        // Same key id, different key material: the key id only selects the key, the signature decides.
        Key signer = new Key();
        Key pinned = new Key();
        String sig = signer.sign(tmp("rootfs"), MANIFEST);
        assertNull(MinisignVerifier.authenticatedComment(sig, TrustedSigningKeys.of(pinned.pubLine())));
    }

    @Test
    public void rejectsTheLegacyNonPrehashedTag() throws Exception {
        Key k = new Key();
        String sig = k.sign(tmp("rootfs"), MANIFEST);
        byte[] blob = Base64.decode(sig.split("\n")[1].trim());
        blob[1] = 'd';                            // "ED" -> "Ed"
        assertNull(MinisignVerifier.authenticatedComment(replaceLine(sig, 1, b64(blob)),
                TrustedSigningKeys.of(k.pubLine())));
    }

    @Test
    public void rejectsMalformedOrMissingSignatures() {
        TrustedSigningKeys keys = TrustedSigningKeys.of(new Key().pubLine());
        assertNull(MinisignVerifier.authenticatedComment(null, keys));
        assertNull(MinisignVerifier.authenticatedComment("not a minisig", keys));
        assertNull(MinisignVerifier.authenticatedComment("a\n!!notbase64!!\ntrusted comment: x\nb\n", keys));
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
