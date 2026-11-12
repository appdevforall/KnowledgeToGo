package org.appdevforall.k2go.download.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The minisign public keys the app trusts for official rootfs tarballs (K2GO-554).
 *
 * <p>A signature names an 8-byte key id; {@link #forKeyId} returns the pinned key for that id, or
 * null when the id is not pinned, so an unknown signer is never trusted (fail closed). Rotation:
 * add the next key to {@code PINNED} and keep the old one during the overlap, then drop it.
 *
 * <p>Source of truth for the pinned value: {@code controller/rootfs-signing.pub}. It is hard-coded
 * here (not read from an asset) so it is covered by the app signature and cannot be swapped at rest.
 */
public final class TrustedSigningKeys {

    // key id C5E45B250781C960 (controller/rootfs-signing.pub). Base64 line only, no comment line.
    private static final String[] PINNED = {
        "RWRgyYEHJVvkxSinsxrA+/xOfzno8cPOFk9GFruR3w54RLFUVHDaH9Vb",
    };

    /** The keys a shipped build trusts. */
    public static final TrustedSigningKeys PRODUCTION = of(PINNED);

    private final Map<String, MinisignVerifier.PublicKey> byKeyId;

    private TrustedSigningKeys(Map<String, MinisignVerifier.PublicKey> byKeyId) {
        this.byKeyId = byKeyId;
    }

    /** Build a set from minisign public keys (a whole .pub file, or just its base64 line). */
    public static TrustedSigningKeys of(String... pubKeys) {
        Map<String, MinisignVerifier.PublicKey> m = new LinkedHashMap<>();
        for (String p : pubKeys) {
            MinisignVerifier.PublicKey k = MinisignVerifier.PublicKey.parse(p);
            m.put(hex(k.keyId), k);
        }
        return new TrustedSigningKeys(Collections.unmodifiableMap(m));
    }

    /** The pinned key for this 8-byte key id, or null when the id is not pinned (not trusted). */
    public MinisignVerifier.PublicKey forKeyId(byte[] keyId) {
        return keyId == null ? null : byKeyId.get(hex(keyId));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x));
        }
        return sb.toString();
    }
}
