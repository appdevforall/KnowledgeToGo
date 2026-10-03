/*
 * ============================================================================
 * Name        : ModuleActions.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-449. One place that declares a content module's "installed" action (the update
 *               action shown once the module is installed), so ModuleActionSheet and
 *               ModuleDetailFragment iterate it instead of each carrying a per-module if block. A new
 *               content module registers an entry here; the shared UI files stop changing per module.
 *               Static config only, no runtime state. Forgejo's repos action stays special for now (it
 *               is status-gated / async), so it is not in this registry yet.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.Activity;
import android.view.View;

import androidx.annotation.Nullable;

import org.appdevforall.k2go.R;

import java.util.HashMap;
import java.util.Map;

public final class ModuleActions {

    private ModuleActions() {}

    /** Runs a module's installed action, anchored to a view (for the snackbar / inline progress). */
    public interface Handler { void run(Activity act, View anchor); }

    /** The update action a module offers once installed: a labeled, iconed row that runs {@link #handler}. */
    public static final class InstalledAction {
        public final int labelRes;
        public final int iconRes;
        public final Handler handler;
        InstalledAction(int labelRes, int iconRes, Handler handler) {
            this.labelRes = labelRes; this.iconRes = iconRes; this.handler = handler;
        }
    }

    // Keyed by the module's YAML key (ModuleCards.Card.key()).
    private static final Map<String, InstalledAction> INSTALLED = new HashMap<>();
    static {
        INSTALLED.put("code_addons", new InstalledAction(
                R.string.k2go_code_addons_update, R.drawable.ic_refresh,
                (act, v) -> org.appdevforall.k2go.addons.presentation.AddonsRefresh.start(act, v)));
        INSTALLED.put("code_assets", new InstalledAction(
                R.string.k2go_code_assets_update, R.drawable.ic_refresh,
                (act, v) -> org.appdevforall.k2go.codeassets.presentation.CodeAssetsRefresh.start(act, v)));
    }

    /** The installed action for {@code key}, or null when the module has none (or is handled specially). */
    @Nullable
    public static InstalledAction installed(@Nullable String key) {
        return key == null ? null : INSTALLED.get(key);
    }
}
