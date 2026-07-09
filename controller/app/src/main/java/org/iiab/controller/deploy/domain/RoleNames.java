/*
 * ============================================================================
 * Name        : RoleNames.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule (ADFA-4629): map a module's local_vars variable base
 *               (e.g. "calibreweb") to its Ansible ROLE directory name
 *               (e.g. "calibre-web"). Upstream IIAB's own runrole documents that
 *               these sometimes differ; we install per-module by calling
 *               `./runrole <role>`, so we must pass the role name, not the var base.
 * ============================================================================
 */
package org.iiab.controller.deploy.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure (framework-free) map from a module's local_vars variable base to its
 * Ansible role directory name.
 *
 * <p>IIAB keeps two identifiers per module: the local_vars variable base used to
 * enable it ({@code <base>_install} / {@code <base>_enabled}) and the role
 * directory under {@code roles/}. For most modules they are identical, but a few
 * diverge. Upstream's {@code runrole} script encodes the same exceptions:
 *
 * <pre>
 *   # Ansible role name &amp; var name sometimes differ :/
 *   if   ROLE_NAME == "calibre-web"      -> ROLE_VAR=calibreweb
 *   elif ROLE_NAME == "iiab-admin"       -> ROLE_VAR=iiab_admin
 *   elif ROLE_NAME == "osm-vector-maps"  -> ROLE_VAR=osm_vector_maps
 * </pre>
 *
 * <p>Our per-module install path calls {@code ./runrole <name>} directly (it does
 * not go through the full playbook), so it must pass the ROLE name. Feeding it the
 * variable base is what made "Books" (calibre-web) fail with
 * {@code The role 'calibreweb' was not found}. This table restores the mapping.
 *
 * <p>No {@code android.*} here so it is unit-testable on a plain JVM.
 */
public final class RoleNames {

    /** varBase -> Ansible role directory name, for the modules where they differ. */
    private static final Map<String, String> ROLE_BY_VAR_BASE;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("calibreweb", "calibre-web");
        // Defensive: not currently in MASTER_ROSTER, but mirror upstream so they
        // work immediately if ever added.
        m.put("iiab_admin", "iiab-admin");
        m.put("osm_vector_maps", "osm-vector-maps");
        ROLE_BY_VAR_BASE = Collections.unmodifiableMap(m);
    }

    private RoleNames() {
        // Static utility; not instantiable.
    }

    /**
     * The Ansible role directory name to hand to {@code runrole} for a module.
     * Returns the mapped role name when the variable base and role name differ,
     * otherwise the value is returned unchanged (they are identical for most
     * modules). {@code null} in yields {@code null} out.
     */
    public static String roleFor(String yamlBaseKey) {
        if (yamlBaseKey == null) {
            return null;
        }
        String role = ROLE_BY_VAR_BASE.get(yamlBaseKey);
        return role != null ? role : yamlBaseKey;
    }
}
