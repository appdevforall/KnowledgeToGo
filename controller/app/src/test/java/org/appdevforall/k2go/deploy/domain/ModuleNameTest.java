package org.appdevforall.k2go.deploy.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link ModuleName} — the allowlist guard that closes D2
 * (command injection via a module name interpolated into a root shell command).
 * Pure JVM, no Android dependencies.
 */
public class ModuleNameTest {

    private static final Set<String> ROSTER = new HashSet<>(Arrays.asList(
            "calibreweb", "code", "kiwix", "kolibri", "maps", "matomo", "dashboard"));

    @Test
    public void acceptsEveryKnownRosterModule() {
        for (String m : ROSTER) {
            assertTrue("roster module rejected: " + m, ModuleName.isAllowed(m, ROSTER));
        }
    }

    @Test
    public void rejectsShellInjectionEvenIfWellFormedPrefix() {
        assertFalse(ModuleName.isAllowed("kiwix; rm -rf /", ROSTER));
        assertFalse(ModuleName.isAllowed("kiwix' && reboot #", ROSTER));
        assertFalse(ModuleName.isAllowed("$(reboot)", ROSTER));
        assertFalse(ModuleName.isAllowed("maps`id`", ROSTER));
        assertFalse(ModuleName.isAllowed("maps\nmatomo", ROSTER));
    }

    @Test
    public void rejectsWellFormedButUnknownModule() {
        // Charset-valid but not in the catalog -> still rejected (allowlist).
        assertFalse(ModuleName.isAllowed("evil", ROSTER));
        assertFalse(ModuleName.isAllowed("wordpress", ROSTER));
    }

    @Test
    public void rejectsNulls() {
        assertFalse(ModuleName.isAllowed(null, ROSTER));
        assertFalse(ModuleName.isAllowed("kiwix", null));
    }

    // --- isWellFormed (the charset rule on its own) ---

    @Test
    public void wellFormedAcceptsCatalogStyleNames() {
        assertTrue(ModuleName.isWellFormed("calibreweb"));
        assertTrue(ModuleName.isWellFormed("kiwix"));
        assertTrue(ModuleName.isWellFormed("a-b_c1"));
    }

    @Test
    public void wellFormedRejectsMetacharsAndCase() {
        assertFalse(ModuleName.isWellFormed("Kiwix"));      // uppercase
        assertFalse(ModuleName.isWellFormed("a b"));        // space
        assertFalse(ModuleName.isWellFormed("a;b"));        // semicolon
        assertFalse(ModuleName.isWellFormed("a'b"));        // quote
        assertFalse(ModuleName.isWellFormed("a/b"));        // slash
        assertFalse(ModuleName.isWellFormed(""));           // empty
        assertFalse(ModuleName.isWellFormed(null));         // null
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) sb.append('a');
        assertFalse(ModuleName.isWellFormed(sb.toString())); // too long
    }
}
