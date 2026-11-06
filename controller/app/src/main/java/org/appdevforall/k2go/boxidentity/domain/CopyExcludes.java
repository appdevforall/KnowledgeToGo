package org.appdevforall.k2go.boxidentity.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The records that a box copy (Clone, Backup) does not carry (K2GO-549). One list, in the asset
 * {@link #ASSET}, with paths relative to the box root. This class anchors each path for the tool
 * that makes the copy, so that "tmp/*" never matches a "tmp" dir deeper in the tree.
 */
public final class CopyExcludes {

    public static final String ASSET = "box-copy-exclude.txt";

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
     * For an rsync daemon "exclude from" file whose module path is the box root: a leading "/"
     * anchors the pattern at the module root.
     */
    public static String forRsync(String text) {
        return join(text, "/");
    }

    /**
     * For GNU tar {@code --anchored --no-wildcards-match-slash --exclude-from}. {@code memberPrefix}
     * is the archive name of the box root, for example "installed-rootfs/iiab/".
     */
    public static String forTar(String text, String memberPrefix) {
        return join(text, memberPrefix);
    }

    private static String join(String text, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String p : patterns(text)) {
            sb.append(prefix).append(p).append('\n');
        }
        return sb.toString();
    }
}
