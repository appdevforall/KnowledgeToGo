/*
 * ============================================================================
 * Name        : ForgejoSeedRule.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-592. The rule for when a Forgejo seed is owed. The Forgejo role
 *               installs an empty forge; the seed adds the admin account, the org and the
 *               example repos. Every module batch that installs Forgejo owes a seed, from
 *               any install path.
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.domain;

public final class ForgejoSeedRule {

    private ForgejoSeedRule() {}

    /** The module key of Forgejo in the module catalog. */
    public static final String FORGEJO = "forgejo";

    /** True when the batch of module keys installs Forgejo. */
    public static boolean owedBy(String[] modules) {
        if (modules == null) return false;
        for (String m : modules) {
            if (FORGEJO.equals(m)) return true;
        }
        return false;
    }
}
