/*
 * ============================================================================
 * Name        : ModuleName.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Domain rule: a module name is only safe to install if it is a
 *               known catalog entry AND contains no shell metacharacters.
 *               Closes tech-debt item D2 (command injection via module name).
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

import java.util.Set;

/**
 * Pure (framework-free) guard for module names that get interpolated into a
 * shell/Ansible command executed <strong>as root inside the container</strong>
 * (see {@code DeployFragment}: {@code sed ... && echo ... && ./runrole NAME}).
 *
 * <p>A crafted name containing a quote, {@code ;}, {@code &&}, {@code $()} etc.
 * would break out of the command — tech-debt item <b>D2</b>. Module names come
 * from a fixed catalog ({@code ModuleRegistry.MASTER_ROSTER}) and round-trip
 * through SharedPreferences, so this enforces an <em>allowlist</em>: the name
 * must be one of the known catalog keys and also be well-formed.
 *
 * <p>No {@code android.*} here so it is unit-testable on a plain JVM and reusable
 * by any other code path that interpolates a module name.
 */
public final class ModuleName {

    private static final int MAX_LEN = 64;

    private ModuleName() {
        // Static utility; not instantiable.
    }

    /**
     * True if {@code name} is one of the {@code known} catalog keys and is
     * well-formed. Fail-closed: anything not explicitly allowed is rejected.
     */
    public static boolean isAllowed(String name, Set<String> known) {
        return name != null && known != null && known.contains(name) && isWellFormed(name);
    }

    /**
     * True if {@code name} is a safe module identifier: a non-empty run of
     * {@code [a-z0-9_-]} (no uppercase, whitespace, quotes or shell
     * metacharacters), at most {@value #MAX_LEN} chars. Matches every key in the
     * current roster (e.g. {@code calibreweb}, {@code kiwix}, {@code maps}).
     */
    public static boolean isWellFormed(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LEN) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
