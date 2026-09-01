package org.appdevforall.k2go.redesign;

import android.app.Activity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.system.domain.Operation;

/**
 * ADFA-5228: one gate for starting a proot module install.
 *
 * <p>A proot install (ExecutionClass STOPPED) runs an Ansible runrole with the server paused and
 * confines the user to the progress screen for a long time, so it asks first — informative, not
 * discouraging. LIVE work (content downloads, the dashboard REST update) does not confine and is
 * never gated. The decision is read from the {@link Operation}'s execution class, not a name list,
 * so a LIVE operation routed here would proceed straight through.
 *
 * <p>Placed at the install-initiation points (the "open the install index" calls) rather than in the
 * index drain, so the wizard/index batch — already confirmed once — is not asked again per module.
 */
public final class InstallConfirm {

    private InstallConfirm() {}

    /**
     * Proceed immediately for LIVE (or unknown) work; for a STOPPED proot install, show an
     * informative confirmation first and run {@code onProceed} only if the user accepts.
     */
    public static void gate(Activity activity, Operation op, Runnable onProceed) {
        if (op == null || op.isLive()) {
            onProceed.run();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.k2go_install_confirm_title)
                .setMessage(R.string.k2go_install_confirm_body)
                .setNegativeButton(R.string.k2go_install_confirm_cancel, null)
                .setPositiveButton(R.string.k2go_install_confirm_go, (d, w) -> onProceed.run())
                .show();
    }
}
