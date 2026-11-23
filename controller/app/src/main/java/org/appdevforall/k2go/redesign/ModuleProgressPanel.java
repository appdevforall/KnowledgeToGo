/*
 * ============================================================================
 * Name        : ModuleProgressPanel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-593. The progress panel of a proot module install
 *               (view_k2go_module_progress): the install bar over the download
 *               card, the same on every install screen.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.install.presentation.EtaText;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;
import org.appdevforall.k2go.install.presentation.ModuleQueueState;

/**
 * The standard progress of a proot module install, started with maps (K2GO-394) and Forgejo
 * (K2GO-593). Two bars, each with one source, and the panel keeps no state of its own:
 * <ol>
 *   <li>The install bar: {@link ModuleQueueRepository}. It shows while the module runs and its role
 *       has a task table (percent >= 0). It starts at 0% before the module's download, so both bars
 *       show while the module downloads.</li>
 *   <li>The download card: {@code ModuleDownloadRepository}, through {@link DownloadCard}.</li>
 * </ol>
 * A new proot module gets both by including the layout and making one panel with its key.
 */
public final class ModuleProgressPanel {

    private final Context ctx;
    private final String module;
    private final View installRow;
    private final LinearProgressIndicator installBar;
    private final TextView installPct, installEta;
    private final DownloadCard downloadCard;

    /**
     * @param root             a view that contains view_k2go_module_progress
     * @param module           the module key ("maps", "forgejo")
     * @param downloadTitleRes the download card title
     */
    public ModuleProgressPanel(@NonNull View root, @NonNull String module, @StringRes int downloadTitleRes) {
        this.ctx = root.getContext();
        this.module = module;
        installRow = root.findViewById(R.id.k2go_mp_install_row);
        installBar = root.findViewById(R.id.k2go_mp_install_bar);
        installPct = root.findViewById(R.id.k2go_mp_install_pct);
        installEta = root.findViewById(R.id.k2go_mp_install_eta);
        downloadCard = new DownloadCard(root, module, downloadTitleRes);
    }

    /** Observe both sources for the life of {@code owner}. */
    public void observe(@NonNull LifecycleOwner owner) {
        ModuleQueueRepository.get().state().observe(owner, this::renderInstall);
        downloadCard.observe(owner);
    }

    private void renderInstall(ModuleQueueState mq) {
        boolean show = mq != null && mq.isInstalling(module) && mq.percent >= 0;
        installRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        installBar.setProgressCompat(mq.percent, true);
        installPct.setText(ctx.getString(R.string.dash_format_pct, mq.percent));
        installEta.setText(EtaText.of(ctx, mq.etaSeconds));
    }
}
