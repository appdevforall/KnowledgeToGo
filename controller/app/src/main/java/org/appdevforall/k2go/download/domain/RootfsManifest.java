package org.appdevforall.k2go.download.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The manifest the bake writes into the minisign trusted comment of a rootfs tarball
 * (ADR-K2GO-554 Amendment 1, A1/A2):
 * {@code k2go-rootfs v1 file=<name> size=<gz bytes> sha256=<gz sha-256> meta4=<meta4 sha-256>
 * usize=<uncompressed tar bytes>}. K2GO-569: {@code usize} is optional; without it a restore takes
 * the full path.
 *
 * <p>The parse is strict. Anything else (another prefix or version, an unknown or repeated key,
 * a malformed value, a missing required key) means "no manifest", so the caller takes the
 * full path.
 */
public final class RootfsManifest {

    private static final String PREFIX = "k2go-rootfs v1 ";
    private static final String HEX64 = "[0-9a-f]{64}";

    public final String file;
    public final long size;
    public final String sha256;
    /** sha-256 of the dated .meta4, or null when absent. Reserved for zero-pass download (A6). */
    public final String meta4;
    /** Uncompressed tar stream size, or -1 when absent. The exact extract total, also above 4 GiB. */
    public final long usize;

    private RootfsManifest(String file, long size, String sha256, String meta4, long usize) {
        this.file = file;
        this.size = size;
        this.sha256 = sha256;
        this.meta4 = meta4;
        this.usize = usize;
    }

    /** The manifest in an authenticated trusted comment, or null when there is none. */
    public static RootfsManifest parse(String trustedComment) {
        if (trustedComment == null || !trustedComment.startsWith(PREFIX)) {
            return null;
        }
        Map<String, String> kv = new HashMap<>();
        for (String token : trustedComment.substring(PREFIX.length()).trim().split(" ")) {
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                return null;
            }
            String key = token.substring(0, eq);
            if (!key.equals("file") && !key.equals("size") && !key.equals("sha256") && !key.equals("meta4")
                    && !key.equals("usize")) {
                return null;
            }
            if (kv.put(key, token.substring(eq + 1)) != null) {
                return null;
            }
        }
        String file = kv.get("file");
        String size = kv.get("size");
        String sha256 = kv.get("sha256");
        String meta4 = kv.get("meta4");
        String usize = kv.get("usize");
        if (file == null || size == null || !size.matches("[0-9]{1,18}")
                || sha256 == null || !sha256.matches(HEX64)
                || (meta4 != null && !meta4.matches(HEX64))
                || (usize != null && !usize.matches("[0-9]{1,18}"))) {
            return null;
        }
        return new RootfsManifest(file, Long.parseLong(size), sha256, meta4,
                usize == null ? -1L : Long.parseLong(usize));
    }

    /** The outcome of the trust decision for one downloaded artifact. */
    public enum Verdict { TRUSTED, BYTES_NOT_VERIFIED, NOT_AUTHENTIC, NO_MANIFEST, MISMATCH, WRONG_TARGET }

    /** The tier and ABI the app asked for, as the server filename tokens (e.g. "full", "armeabi-v7a"). */
    public static final class Target {
        public final String tier;
        public final String abi;

        public Target(String tier, String abi) {
            this.tier = tier;
            this.abi = abi;
        }
    }

    /**
     * The trust decision (ADR Amendment 1, A3). TRUSTED needs integrity (the bytes passed the
     * SHA-256 check against {@code sha256Hex}, reported as {@code bytesVerified}) and identity (an
     * authenticated manifest that names this exact file, size and sha-256, for the tier and ABI the
     * app asked for). The target check stops an official tarball of another ABI or tier, offered by
     * an unsigned meta4, from being trusted.
     *
     * @param authenticatedComment the trusted comment after its signature verified, or null
     */
    public static Verdict judge(boolean bytesVerified, String authenticatedComment,
                                String fileName, long sizeBytes, String sha256Hex, Target expected) {
        if (!bytesVerified) return Verdict.BYTES_NOT_VERIFIED;
        if (authenticatedComment == null) return Verdict.NOT_AUTHENTIC;
        RootfsManifest m = parse(authenticatedComment);
        if (m == null) return Verdict.NO_MANIFEST;
        if (!m.vouchesFor(fileName, sizeBytes, sha256Hex)) return Verdict.MISMATCH;
        return m.isFor(expected) ? Verdict.TRUSTED : Verdict.WRONG_TARGET;
    }

    /**
     * True when the signed file name is the bake artifact for this tier and ABI:
     * {@code k2go_<yyyy.ddd>_<tier>_<sha>_<abi>.tar.gz} (tools/rootfs-builder/build-iiab-rootfs.sh).
     */
    public boolean isFor(Target expected) {
        return expected != null && expected.tier != null && expected.abi != null
                && file.matches("k2go_[0-9]{4}\\.[0-9]{3}_" + Pattern.quote(expected.tier)
                        + "_[0-9a-f]+_" + Pattern.quote(expected.abi) + "\\.tar\\.gz");
    }

    /** K2GO-569: true when the signed file name is a bake artifact of any tier for this ABI (a restore). */
    public boolean isForAbi(String abi) {
        return abi != null && file.matches("k2go_[0-9]{4}\\.[0-9]{3}_[a-z0-9-]+_[0-9a-f]+_"
                + Pattern.quote(abi) + "\\.tar\\.gz");
    }

    /**
     * K2GO-569: what a signed restore may trust about the tarball named {@code fileName}, or
     * {@code null}: this manifest must name that file, be a bake artifact for {@code abi}, and carry
     * {@code usize}. The copy then checks the size and sha-256.
     */
    public org.appdevforall.k2go.deploy.domain.ExpectedArchive expectedFor(String fileName, String abi) {
        return usize > 0 && file.equals(fileName) && isForAbi(abi)
                ? new org.appdevforall.k2go.deploy.domain.ExpectedArchive(size, sha256, usize) : null;
    }

    /** True when this manifest names exactly this artifact (file name, size and sha-256). */
    public boolean vouchesFor(String fileName, long sizeBytes, String sha256Hex) {
        return file.equals(fileName)
                && size == sizeBytes
                && sha256Hex != null
                && sha256.equals(sha256Hex.trim().toLowerCase(Locale.ROOT));
    }
}
