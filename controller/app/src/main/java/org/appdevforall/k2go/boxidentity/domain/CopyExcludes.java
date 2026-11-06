package org.appdevforall.k2go.boxidentity.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The records that a box copy (Clone, Backup) does not carry (K2GO-549). One list, in the asset
 * {@link #ASSET}, with paths relative to the box root. This class anchors each path for the tool
 * that makes the copy, so that "tmp/*" never matches a "tmp" dir deeper in the tree.
 */
public final class CopyExcludes {

    public static final String ASSET = "box-copy-exclude.txt";

    /**
     * Bind targets in the box root (PRootEngine) that cannot be read while the box is stopped.
     * rsync must not open them: one read error makes it skip every deletion on the receiver. The
     * receiver must not delete them either. Add a bind target here if its stub is unreadable.
     */
    public static final List<String> MOUNT_POINTS = Collections.singletonList("sdcard");

    private CopyExcludes() {
    }

    /** The patterns of {@code text}, without comments and blank lines. */
    public static List<String> patterns(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String p = line.trim();
            if (p.isEmpty() || p.startsWith("#")) continue;
            while (p.startsWith("/")) p = p.substring(1);
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * For an rsync "exclude from" file whose transfer root is the box root (the Clone daemon and
     * the pull): a leading "/" anchors the pattern at that root. A mount point is excluded as a
     * whole dir, so neither side opens it.
     */
    public static String forRsync(String text) {
        StringBuilder sb = new StringBuilder(join(text, "/"));
        for (String m : MOUNT_POINTS) sb.append('/').append(m).append("/\n");
        return sb.toString();
    }

    /**
     * rsync filter rules that keep the receiver's mount points: with --delete-excluded, an
     * excluded dir is deleted unless a protect rule comes first.
     */
    public static List<String> rsyncProtectRules() {
        List<String> out = new ArrayList<>();
        for (String m : MOUNT_POINTS) out.add("P /" + m + "/");
        return out;
    }

    /**
     * For GNU tar {@code --anchored --no-wildcards-match-slash --exclude-from}. {@code memberPrefix}
     * is the archive name of the box root, for example "installed-rootfs/iiab/". A mount point
     * keeps its empty dir in the archive; --ignore-failed-read covers the unreadable stub.
     */
    public static String forTar(String text, String memberPrefix) {
        StringBuilder sb = new StringBuilder(join(text, memberPrefix));
        for (String m : MOUNT_POINTS) sb.append(memberPrefix).append(m).append("/*\n");
        return sb.toString();
    }

    private static String join(String text, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String p : patterns(text)) {
            sb.append(prefix).append(p).append('\n');
        }
        return sb.toString();
    }
}
