/*
 * ============================================================================
 * Name        : ForgejoBinaryPin.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-593. The local_vars line that makes the forgejo role use
 *               the binary that dash-node downloaded. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

/**
 * dash-node downloads the Forgejo binary to {@code /library/forgejo/bin/forgejo-<ver>} (job type
 * {@code forgejo-bin}). The role resolves {@code forgejo_version: latest} on its own, so the app pins
 * the version that dash-node got, and the role's get_url finds the file and skips its download. The
 * role then still checks the GPG signature of the file.
 *
 * <p>The pin is for this one run: a later update must resolve the newest release again. So the line
 * carries a marker, and {@link #UNPIN} removes only the marked line, never a pin the owner wrote.
 * The version comes from the box, and it goes into a root shell command, so it is checked here first.
 */
public final class ForgejoBinaryPin {

    private ForgejoBinaryPin() {}

    /** The module that owns this download. */
    public static final String MODULE = "forgejo";
    /** The dash-node job type. */
    public static final String JOB_TYPE = "forgejo-bin";

    private static final String MARKER = "# k2go-forgejo-bin";
    private static final String LOCAL_VARS = "/etc/iiab/local_vars.yml";

    /** Removes the marked pin. Safe to run when there is none. */
    public static final String UNPIN = "sed -i '/" + MARKER + "$/d' " + LOCAL_VARS;

    /** The role's assert: X.Y.Z. */
    public static boolean isVersion(String v) {
        return v != null && v.matches("[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    /**
     * The forgejo_version that the owner wrote in local_vars (a line without the marker), or null when
     * there is none or it is "latest". With an owner pin the app does not download: the role
     * downloads the version the owner chose, as before.
     */
    public static String ownerPin(java.util.List<String> localVarsLines) {
        if (localVarsLines == null) return null;
        String pin = null;
        for (String raw : localVarsLines) {
            if (raw == null || raw.trim().endsWith(MARKER)) continue;
            java.util.regex.Matcher m = OWNER_LINE.matcher(raw);
            if (m.matches()) pin = m.group(1);   // the last one wins, as in YAML
        }
        return pin == null || "latest".equals(pin) ? null : pin;
    }

    private static final java.util.regex.Pattern OWNER_LINE = java.util.regex.Pattern.compile(
            "^forgejo_version[ \\t]*:[ \\t]*[\"']?([^\"'#\\s]+)[\"']?[ \\t]*(#.*)?$");

    /**
     * The batch order with Forgejo first. Its download runs before the server stop, while its row
     * already runs, so a module before it in the list would look done. The others keep their order.
     */
    public static String[] downloadFirst(String[] modules) {
        if (modules == null) return null;
        java.util.List<String> out = new java.util.ArrayList<>(modules.length);
        for (String m : modules) if (MODULE.equals(m)) out.add(m);
        for (String m : modules) if (!MODULE.equals(m)) out.add(m);
        return out.toArray(new String[0]);
    }

    /**
     * The shell command that pins {@code version}, or null when the version is not X.Y.Z. A pin that a
     * killed run left is removed first, so there is never more than one marked line.
     */
    public static String pinCommand(String version) {
        if (!isVersion(version)) return null;
        return UNPIN + " && echo 'forgejo_version: \"" + version + "\"  " + MARKER + "' >> " + LOCAL_VARS;
    }
}
