/*
 * ============================================================================
 * Name        : InstalledModules.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5104. What the installer's own flags say is installed.
 *               Pure JVM, no Android, no I/O.
 * ============================================================================
 */
package org.iiab.controller.system.domain;

import org.json.JSONObject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads "what is installed" out of the installer's own flags, rather than out of who answers.
 *
 * <p><b>Why the flags and not a probe.</b> A probe answers "is this platform serving right now",
 * which is a different question and a worse one for this purpose: it needs the box up, it needs
 * the network stack, and it turns every kind of silence into "absent". The flags are written by
 * the thing that does the installing, they live on disk, and they can be read with the box
 * stopped.
 *
 * <p><b>Why this is allowed to be remembered.</b> A module install only ever adds. There is no
 * uninstall today, so within one system's life the set can grow and cannot shrink — which is
 * what makes a stored answer safe, where a stored answer about a *running* service would not be.
 * The exit from that is a system replacement (reset, restore, clone, reinstall), and the flags
 * handle it for free: they live inside the rootfs, so a replacement takes them with it. Nothing
 * has to remember to invalidate anything.
 *
 * <p><b>What this is not.</b> Intention, not result — {@code InstallService} writes
 * {@code <key>_install: True} before the runrole and reverts it if the run fails, so a process
 * death between the two leaves a flag claiming an install that never finished. That window is
 * narrow and it is why a live probe still gets to confirm or correct this. It is not a reason to
 * prefer the probe: a flag that is wrong for minutes beats a probe that is wrong whenever the box
 * is off.
 */
public final class InstalledModules {

    /** The suffix the installer appends to a module's yaml base key. */
    private static final String INSTALL_SUFFIX = "_install";

    private InstalledModules() {
    }

    /**
     * Whether the flags claim this module is installed.
     *
     * <p>{@code <key>_install} only. The installer also writes {@code <key>_enabled}, but that is
     * a different statement — a module can be installed and switched off — and reading both would
     * make "installed" mean "installed and running", which is the conflation this ticket exists to
     * undo.
     *
     * @param flags        parsed {@code local_vars.yml} flags, as {@code LocalVarsYamlParser}
     *                     produces them; null is treated as "nothing known"
     * @param yamlBaseKey  the module's key in {@code local_vars.yml}, e.g. {@code calibreweb}
     */
    public static boolean isInstalled(JSONObject flags, String yamlBaseKey) {
        if (flags == null || yamlBaseKey == null || yamlBaseKey.isEmpty()) {
            return false;
        }
        return flags.optBoolean(yamlBaseKey + INSTALL_SUFFIX, false);
    }

    /**
     * The subset of {@code knownKeys} the flags claim is installed.
     *
     * <p>Restricted to the roster on purpose: {@code local_vars.yml} carries a flag for every
     * IIAB role that exists, most of which this app does not present and cannot install. Reading
     * them all would put names on screen that no card can render.
     */
    public static Set<String> from(JSONObject flags, Collection<String> knownKeys) {
        if (flags == null || knownKeys == null) {
            return Collections.emptySet();
        }
        Set<String> installed = new HashSet<>();
        for (String key : knownKeys) {
            if (isInstalled(flags, key)) {
                installed.add(key);
            }
        }
        return installed;
    }

    /**
     * What the flags amount to as evidence about one module.
     *
     * <p>The asymmetry matters and is the reason this returns {@link PlatformPresence.Evidence}
     * rather than a boolean. A readable file that does not claim the module is a real
     * {@code ABSENT} — the installer would have written the flag — and that is stronger than a
     * 404, which only says nothing is serving that path right now. An unreadable file is
     * {@code NONE}: no rootfs, or no system yet, and the caller must not read that as absence.
     *
     * @param flags null when the file could not be read at all
     */
    public static PlatformPresence.Evidence evidenceFor(JSONObject flags, String yamlBaseKey) {
        if (flags == null) {
            return PlatformPresence.Evidence.NONE;
        }
        return isInstalled(flags, yamlBaseKey)
                ? PlatformPresence.Evidence.PRESENT
                : PlatformPresence.Evidence.ABSENT;
    }
}
