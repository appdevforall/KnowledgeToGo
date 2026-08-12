package org.iiab.controller.system.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.util.LocalVarsYamlParser;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link InstalledModules} — the rule that reads "what is installed" out of the
 * installer's own flags instead of out of who answers a probe. Pure JVM, no emulator.
 *
 * <p>Fed through the real {@link LocalVarsYamlParser} wherever the input is a file's worth of
 * text, so the two are pinned together: the rule is only as good as what the parser hands it, and
 * a change to either that breaks the pair should fail here.
 */
public class InstalledModulesTest {

    private static final Set<String> ROSTER = new HashSet<>(Arrays.asList(
            "kolibri", "calibreweb", "kiwix", "code", "matomo", "maps"));

    private static JSONObject parse(String yaml) {
        return LocalVarsYamlParser.parseToJson(yaml);
    }

    // ---- the ordinary reading ----------------------------------------------

    @Test
    public void anInstallFlagMeansInstalled() {
        JSONObject flags = parse("kolibri_install: True\nkiwix_install: False\n");
        assertTrue(InstalledModules.isInstalled(flags, "kolibri"));
        assertFalse(InstalledModules.isInstalled(flags, "kiwix"));
    }

    @Test
    public void aModuleWithNoFlagAtAllIsNotInstalled() {
        assertFalse(InstalledModules.isInstalled(parse("kolibri_install: True\n"), "matomo"));
    }

    /**
     * Installed and enabled are different statements. Reading both would make "installed" mean
     * "installed and running", which is the conflation this work exists to undo.
     */
    @Test
    public void enabledOnItsOwnIsNotInstalled() {
        JSONObject flags = parse("calibreweb_enabled: True\n");
        assertFalse(InstalledModules.isInstalled(flags, "calibreweb"));
    }

    @Test
    public void installedWhileDisabledIsStillInstalled() {
        JSONObject flags = parse("calibreweb_install: True\ncalibreweb_enabled: False\n");
        assertTrue(InstalledModules.isInstalled(flags, "calibreweb"));
    }

    // ---- the set, and why it is restricted ---------------------------------

    @Test
    public void theSetIsTheRosterIntersection() {
        JSONObject flags = parse(
                "kolibri_install: True\n"
                        + "calibreweb_install: yes\n"
                        + "kiwix_install: False\n");
        assertEquals(new HashSet<>(Arrays.asList("kolibri", "calibreweb")),
                InstalledModules.from(flags, ROSTER));
    }

    /**
     * local_vars carries a flag for every IIAB role that exists, most of which this app neither
     * presents nor can install. Reading them all would put names on screen no card can render.
     */
    @Test
    public void rolesOutsideTheRosterAreIgnored() {
        JSONObject flags = parse("nextcloud_install: True\nosm_vector_maps_install: True\n");
        assertTrue(InstalledModules.from(flags, ROSTER).isEmpty());
    }

    @Test
    public void nothingKnownYieldsAnEmptySet() {
        assertTrue(InstalledModules.from(null, ROSTER).isEmpty());
        assertTrue(InstalledModules.from(parse(""), ROSTER).isEmpty());
        assertTrue(InstalledModules.from(parse("kolibri_install: True\n"), Collections.emptySet())
                .isEmpty());
    }

    // ---- the asymmetry that makes this worth an Evidence -------------------

    /**
     * The whole point of returning Evidence rather than a boolean: a readable file that does not
     * claim the module is a real absence, because the installer would have written the flag. An
     * unreadable file is not — and a caller that cannot tell those apart is the bug.
     */
    @Test
    public void anUnreadableFileIsSilence_notAbsence() {
        assertEquals(PlatformPresence.Evidence.NONE,
                InstalledModules.evidenceFor(null, "kolibri"));
    }

    @Test
    public void areadableFileWithoutTheFlagIsAbsence() {
        assertEquals(PlatformPresence.Evidence.ABSENT,
                InstalledModules.evidenceFor(parse("kiwix_install: True\n"), "kolibri"));
    }

    @Test
    public void areadableFileWithTheFlagIsPresence() {
        assertEquals(PlatformPresence.Evidence.PRESENT,
                InstalledModules.evidenceFor(parse("kolibri_install: True\n"), "kolibri"));
    }

    /** Silence must not be resolved as absent; absence must. That is what the caller acts on. */
    @Test
    public void resolveKeepsSilenceOnTheSafeSide() {
        assertTrue(PlatformPresence.resolve(InstalledModules.evidenceFor(null, "kolibri")));
        assertFalse(PlatformPresence.resolve(
                InstalledModules.evidenceFor(parse("kiwix_install: True\n"), "kolibri")));
    }

    // ---- what the parser actually survives ---------------------------------

    /**
     * Pinned deliberately. LocalVarsYamlParser is not a YAML parser (tech-debt D14) — it splits on
     * the first colon and knows nothing about nesting, quoting or inline comments. That was
     * tolerable while it fed a cosmetic grid; it is load-bearing now, so the shapes it does and
     * does not survive are written down rather than assumed.
     */
    @Test
    public void theParsersKnownLimitsAreRecordedHere() {
        assertTrue(InstalledModules.isInstalled(parse("kolibri_install: yes"), "kolibri"));
        assertTrue(InstalledModules.isInstalled(parse("kolibri_install: 1"), "kolibri"));
        assertFalse(InstalledModules.isInstalled(parse("# kolibri_install: True"), "kolibri"));

        // Indented keys are read as top level: the parser trims, so nesting is invisible to it.
        assertTrue(InstalledModules.isInstalled(parse("  kolibri_install: True"), "kolibri"));

        // An inline comment is part of the value, so the flag reads false. A real local_vars.yml
        // written by InstallService never has one; a hand-edited file could.
        assertFalse(InstalledModules.isInstalled(parse("kolibri_install: True # on"), "kolibri"));
    }
}
