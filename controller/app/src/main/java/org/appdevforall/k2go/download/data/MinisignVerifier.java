package org.appdevforall.k2go.download.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

/**
 * Verifies a minisign signature over a file (K2GO-554).
 *
 * <p>minisign public key, base64 of 42 bytes: "Ed"(2) + keyId(8) + Ed25519 pubkey(32).
 *
 * <p>minisign signature file, four lines:
 * <pre>
 *   untrusted comment: ...                         (not authenticated)
 *   &lt;base64 of 74 bytes&gt;  = sigAlg(2) + keyId(8) + Ed25519 signature(64)
 *   trusted comment: ...                           (authenticated, see global signature)
 *   &lt;base64 of 64 bytes&gt;  = Ed25519 global signature over ( signature(64) || trustedComment )
 * </pre>
 * sigAlg "ED" is the prehashed form: the signed message is the BLAKE2b-512 digest of the
 * file. This verifier accepts only "ED", so the digest is streamed and a multi-GB rootfs
 * never loads into memory (the reason BouncyCastle is used instead of a byte[]-only API).
 */
public final class MinisignVerifier {

    private static final String TRUSTED_PREFIX = "trusted comment: ";

    private MinisignVerifier() {}

    /** A parsed minisign public key. */
    public static final class PublicKey {
        final byte[] keyId; // 8 bytes
        final byte[] key;   // 32 bytes Ed25519

        PublicKey(byte[] keyId, byte[] key) {
            this.keyId = keyId;
            this.key = key;
        }

        /** Parse a minisign .pub (the whole 2-line file, or just its base64 line). */
        public static PublicKey parse(String pubContent) {
            byte[] raw = base64(lastKeyLine(pubContent));
            if (raw.length != 42 || raw[0] != 'E' || raw[1] != 'd') {
                throw new IllegalArgumentException("not a minisign Ed25519 public key");
            }
            return new PublicKey(Arrays.copyOfRange(raw, 2, 10), Arrays.copyOfRange(raw, 10, 42));
        }
    }

    /**
     * @return true iff {@code minisigContent} is a valid prehashed minisign signature over
     *         {@code file}, produced by {@code pub}.
     */
    public static boolean verify(File file, String minisigContent, PublicKey pub) throws IOException {
        String[] lines = minisigContent.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        if (lines.length < 4) {
            return false;
        }

        byte[] sigBlob = base64(lines[1].trim());
        if (sigBlob.length != 74) {
            return false;
        }
        // "ED" = prehashed BLAKE2b. We reject the legacy "Ed" form on purpose: only the
        // prehashed form can be verified without loading the whole file into memory.
        if (sigBlob[0] != 'E' || sigBlob[1] != 'D') {
            return false;
        }
        if (!Arrays.equals(Arrays.copyOfRange(sigBlob, 2, 10), pub.keyId)) {
            return false;
        }
        byte[] signature = Arrays.copyOfRange(sigBlob, 10, 74); // 64 bytes

        String trustedComment = lines[2];
        if (trustedComment.startsWith(TRUSTED_PREFIX)) {
            trustedComment = trustedComment.substring(TRUSTED_PREFIX.length());
        }
        byte[] globalSig = base64(lines[3].trim());
        if (globalSig.length != 64) {
            return false;
        }

        // 1) main signature over the streamed BLAKE2b-512 of the file.
        byte[] digest = blake2b512(file);
        if (!Ed25519.verify(signature, 0, pub.key, 0, digest, 0, digest.length)) {
            return false;
        }

        // 2) global signature binds the trusted comment to the signature.
        byte[] tc = trustedComment.getBytes(StandardCharsets.UTF_8);
        byte[] globalMsg = new byte[signature.length + tc.length];
        System.arraycopy(signature, 0, globalMsg, 0, signature.length);
        System.arraycopy(tc, 0, globalMsg, signature.length, tc.length);
        return Ed25519.verify(globalSig, 0, pub.key, 0, globalMsg, 0, globalMsg.length);
    }

    /** The 8-byte key id a signature names, or null when the content is malformed. */
    public static byte[] signatureKeyId(String minisigContent) {
        try {
            String[] lines = minisigContent.replace("\r\n", "\n").replace("\r", "\n").split("\n");
            if (lines.length < 2) {
                return null;
            }
            byte[] sigBlob = base64(lines[1].trim());
            if (sigBlob.length != 74) {
                return null;
            }
            return Arrays.copyOfRange(sigBlob, 2, 10);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Verify against the pinned key that the signature's key id names. Returns false (not
     * trusted) when the key id is not pinned, so an unknown signer is never trusted.
     */
    public static boolean verifyTrusted(File file, String minisigContent, TrustedSigningKeys keys)
            throws IOException {
        byte[] keyId = signatureKeyId(minisigContent);
        if (keyId == null) {
            return false;
        }
        PublicKey pub = keys.forKeyId(keyId);
        if (pub == null) {
            return false;            // unknown key id: not trusted (K2GO-554 D9, fail closed)
        }
        return verify(file, minisigContent, pub);
    }

    /**
     * The app's trust decision: true only when {@code file} carries a valid signature from a
     * pinned key. Never throws. Any problem (no or malformed signature, unreadable file, unknown
     * key, bad signature) means "not trusted", so the caller takes the full, slow path
     * (K2GO-554 D9, fail closed).
     */
    public static boolean isTrusted(File file, String minisigContent, TrustedSigningKeys keys) {
        try {
            return minisigContent != null && verifyTrusted(file, minisigContent, keys);
        } catch (Exception e) {
            return false;
        }
    }

    /** Streaming BLAKE2b-512, so a multi-GB file never loads into memory. */
    private static byte[] blake2b512(File file) throws IOException {
        Blake2bDigest d = new Blake2bDigest(512);
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
        }
        byte[] out = new byte[64];
        d.doFinal(out, 0);
        return out;
    }

    // BouncyCastle's Base64 works on the host JVM and on every Android API level (unlike
    // java.util.Base64, which is API 26+), so one code path serves the unit test and minSdk 24.
    private static byte[] base64(String s) {
        return org.bouncycastle.util.encoders.Base64.decode(s.trim());
    }

    private static String lastKeyLine(String content) {
        String last = null;
        for (String ln : content.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            String t = ln.trim();
            if (!t.isEmpty() && !t.startsWith("untrusted comment:") && !t.startsWith("trusted comment:")) {
                last = t;
            }
        }
        if (last == null) {
            throw new IllegalArgumentException("no key line in minisign public key");
        }
        return last;
    }
}
