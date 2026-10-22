/*
 * ============================================================================
 * Name        : ModuleActions.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-449. One place that declares a content module's "installed" action (the update
 *               action shown once the module is installed), so ModuleActionSheet and
 *               ModuleDetailFragment iterate it instead of each carrying a per-module if block. A new
 *               content module registers an entry here; the shared UI files stop changing per module.
 *               Static config only, no runtime state. An entry may carry a Gate: a check run off the
 *               main thread (it may hit the box) that decides whether to offer the action right now.
 *               Forgejo's "Update repos" is such a gated entry. Forgejo's RICHER detail-screen flow
 *               (install-repos / blocked / update, the repos opt-in checkbox, and the seed prefs) is
 *               intrinsically Forgejo-specific and stays in ModuleDetailFragment, not here.
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

    /** Decides whether an installed action should be offered now. Run OFF the main thread (it may hit
     *  the box), so a module can gate its action on live server status. */
    public interface Gate { boolean canShow(); }

    /** The update action a module offers once installed: a labeled, iconed row that runs {@link #handler}.
     *  When {@link #gate} is null the row always shows; otherwise the UI runs the gate off the main thread
     *  and shows the row only if it returns true. */
    public static final class InstalledAction {
        public final int labelRes;
        public final int iconRes;
        public final Handler handler;
        @Nullable public final Gate gate;
        InstalledAction(int labelRes, int iconRes, Handler handler) { this(labelRes, iconRes, handler, null); }
        InstalledAction(int labelRes, int iconRes, Handler handler, @Nullable Gate gate) {
            this.labelRes = labelRes; this.iconRes = iconRes; this.handler = handler; this.gate = gate;
        }
    }

    // Keyed by the module's YAML key (ModuleCards.Card.key()).
    private static final Map<String, InstalledAction> INSTALLED = new HashMap<>();
    static {
        INSTALLED.put("code_addons", new InstalledAction(
                R.string.k2go_code_addons_update, R.drawable.ic_refresh,
                (act, v) -> org.appdevforall.k2go.redesign.ContentUpdaterView.mount(
                        act, v, org.appdevforall.k2go.addons.presentation.AddonsUpdater.INSTANCE)));
        INSTALLED.put("code_assets", new InstalledAction(
                R.string.k2go_code_assets_update, R.drawable.ic_refresh,
                (act, v) -> org.appdevforall.k2go.redesign.ContentUpdaterView.mount(
                        act, v, org.appdevforall.k2go.codeassets.presentation.CodeAssetsUpdater.INSTANCE)));
        // K2GO-422 / K2GO-449: Forgejo "Update repos", gated on live box status (manageable AND repos
        // present). The gate reads ForgejoStatusClient off the main thread. The module detail screen keeps
        // its own richer Forgejo flow (install-repos / blocked / update plus the repos opt-in); this entry
        // covers the simple gated action the module action sheet offers.
        INSTALLED.put("forgejo", new InstalledAction(
                R.string.k2go_forgejo_update_repos, R.drawable.ic_refresh,
                (act, v) -> org.appdevforall.k2go.forgejo.presentation.ForgejoRepoRefresh.start(act, v),
                () -> {
                    org.appdevforall.k2go.forgejo.data.ForgejoStatusClient.Status st =
                            new org.appdevforall.k2go.forgejo.data.ForgejoStatusClient().fetch();
                    return st != null && st.canUpdateRepos();
                }));
    }

    /** The installed action for {@code key}, or null when the module has none. */
    @Nullable
    public static InstalledAction installed(@Nullable String key) {
        return key == null ? null : INSTALLED.get(key);
    }
}
