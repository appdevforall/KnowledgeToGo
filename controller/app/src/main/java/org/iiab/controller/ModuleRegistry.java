/*
 * ============================================================================
 * Name        : ModuleRegistry.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Centralized registry for IIAB modules and their configuration
 * ============================================================================
 */

package org.iiab.controller;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModuleRegistry {

    public static class IiabModule {
        public String endpoint;
        public int nameResId;
        public boolean requires64Bit;
        public String yamlBaseKey; // The exact prefix in local_vars_android.yml before _install or _enabled

        public IiabModule(String endpoint, int nameResId, boolean requires64Bit, String yamlBaseKey) {
            this.endpoint = endpoint;
            this.nameResId = nameResId;
            this.requires64Bit = requires64Bit;
            this.yamlBaseKey = yamlBaseKey;
        }
    }

    // THE MASTER ROSTER: Centralized list for Dashboard, Deploy, and background checks.
    public static final List<IiabModule> MASTER_ROSTER = Arrays.asList(
            new IiabModule("books", R.string.dash_books, false, "calibreweb"), // var base calibreweb_install; Ansible role dir is calibre-web (see RoleNames, ADFA-4629)
            new IiabModule("code", R.string.dash_code, false, "code"),         // YAML uses code_install
            new IiabModule("kiwix", R.string.dash_kiwix, true, "kiwix"),       // YAML uses kiwix_install. TRUE = Hidden on 32-bit!
            new IiabModule("kolibri", R.string.dash_kolibri, false, "kolibri"), // YAML uses kolibri_install
            new IiabModule("maps", R.string.dash_maps, false, "maps"),         // YAML uses maps_install
            new IiabModule("matomo", R.string.dash_matomo, false, "matomo")   // YAML uses matomo_install
            // ADFA-4842: "dashboard" is intentionally NOT in the roster. It is no longer an
            // application/module — it became the REST API core of K2Go; without it maps FQR and all
            // REST content downloads fail. It is part of the core system, not a user-installable module.
    );

    /**
     * The set of valid module YAML keys. This is the single allowlist for any
     * value interpolated into a shell/Ansible command (see D2). Derived from
     * {@link #MASTER_ROSTER} so the catalog stays the single source of truth.
     */
    public static Set<String> validYamlKeys() {
        Set<String> keys = new HashSet<>();
        for (IiabModule m : MASTER_ROSTER) {
            keys.add(m.yamlBaseKey);
        }
        return keys;
    }
}
