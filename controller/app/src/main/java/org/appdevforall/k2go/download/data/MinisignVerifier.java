package org.appdevforall.k2go.download.data;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.bouncycastle.math.ec.rfc8032.Ed25519;

/**
 * Authenticates the trusted comment of a minisign signature (K2GO-554, ADR Amendment 1 A2).
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
 * The app checks only the global signature: one Ed25519 verify, no read of the signed file. The
 * trusted comment carries the manifest (sha-256 and size of the file), and the app binds the bytes
 * with its own SHA-256 pass. The main signature (BLAKE2b over the whole file) is for
 * {@code minisign -Vm} on a PC; on a phone with no hardware crypto it costs minutes.
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
     * The trusted comment of {@code minisigContent} when its global signature verifies under the
     * pinned key its key id names; otherwise null (no or malformed signature, unknown key id, legacy
     * non-prehashed tag, bad signature). Never throws: any problem means "not trusted" (fail closed).
     */
    public static String authenticatedComment(String minisigContent, TrustedSigningKeys keys) {
        try {
            if (minisigContent == null) {
                return null;
            }
            String[] lines = minisigContent.replace("\r\n", "\n").replace("\r", "\n").split("\n");
            if (lines.length < 4 || !lines[2].startsWith(TRUSTED_PREFIX)) {
                return null;
            }
            byte[] sigBlob = base64(lines[1].trim());
            // "ED" = prehashed. The bake writes only this form; reject anything else.
            if (sigBlob.length != 74 || sigBlob[0] != 'E' || sigBlob[1] != 'D') {
                return null;
            }
            PublicKey pub = keys.forKeyId(Arrays.copyOfRange(sigBlob, 2, 10));
            if (pub == null) {
                return null;            // unknown key id: not trusted (fail closed)
            }
            byte[] globalSig = base64(lines[3].trim());
            if (globalSig.length != 64) {
                return null;
            }
            String comment = lines[2].substring(TRUSTED_PREFIX.length());
            byte[] tc = comment.getBytes(StandardCharsets.UTF_8);
            byte[] globalMsg = new byte[64 + tc.length];
            System.arraycopy(sigBlob, 10, globalMsg, 0, 64);
            System.arraycopy(tc, 0, globalMsg, 64, tc.length);
            return Ed25519.verify(globalSig, 0, pub.key, 0, globalMsg, 0, globalMsg.length)
                    ? comment : null;
        } catch (RuntimeException e) {
            return null;
        }
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
