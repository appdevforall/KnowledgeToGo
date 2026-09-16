package org.appdevforall.k2go.networkpolicy.presentation;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.networkpolicy.data.NetworkCostAdmission;
import org.appdevforall.k2go.networkpolicy.data.SessionMeteredConsentStore;
import org.appdevforall.k2go.ui.dialog.BrandDialog;
import org.appdevforall.k2go.util.Snackbars;

/**
 * The user-facing PROMPT for a costed start (ADR-395). A commit point (a Download
 * button) wraps its start so the user is asked before spending metered data:
 *
 * <pre>NetworkPolicyGate.guardHeavyStart(activity, () -&gt; a.startZimDownload());</pre>
 *
 * <p>Stateless, like {@code OpReturnNavigator}: it owns no "is metered" flag. It
 * reads the decision from {@link NetworkCostAdmission} (the one classify + consent
 * + policy source) and either proceeds, asks, or reports offline.
 *
 * <p>This gate is only the PROMPT. The actual hold is enforced headless in
 * {@code ContentAdmission} (via {@link NetworkCostAdmission}), which every content
 * drain already consults -- so a banked order never starts on metered data without
 * consent even if it was never routed through a commit point (the wizard-bank path,
 * a background re-drain). Banking must therefore happen BEFORE this gate: an order
 * declined or offline here stays queued and drains once the network is free or
 * consent is given, rather than being lost (ADR-395 sec.10).
 */
public final class NetworkPolicyGate {

    private NetworkPolicyGate() {}

    public static void guardHeavyStart(@NonNull Activity activity, @NonNull Runnable onProceed) {
        switch (NetworkCostAdmission.decideNow(activity)) {
            case ALLOW:
                onProceed.run();
                return;
            case NEEDS_CONSENT:
                new BrandDialog(activity)
                        .setTitle(R.string.k2go_netpolicy_metered_title)
                        .setMessage(R.string.k2go_netpolicy_metered_msg)
                        .setPositive(R.string.k2go_netpolicy_continue, () -> {
                            SessionMeteredConsentStore.get().grant();
                            onProceed.run();
                        })
                        .setNegative(R.string.k2go_netpolicy_not_now, null)
                        .show();
                return;
            case BLOCKED_NO_NETWORK:
            default:
                View root = activity.findViewById(android.R.id.content);
                if (root != null) {
                    Snackbars.make(root, R.string.k2go_netpolicy_offline).show();
                }
        }
    }
}
