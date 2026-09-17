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
 * <p>For the banked content streams (ZIM/Books/Kolibri) the actual HOLD is enforced
 * headless in {@code ContentAdmission}; declining here just leaves the order queued,
 * so the two-arg form takes no decline action. A direct, non-banked start (FQR maps,
 * which is a user-driven Operation, not a banked ContentType, and so is NOT covered
 * by ContentAdmission) uses the three-arg form to undo its own UI on decline -- e.g.
 * clear the drawn map region -- since there is no queue to fall back on.
 */
public final class NetworkPolicyGate {

    private NetworkPolicyGate() {}

    /** Two-arg form: decline/offline is a no-op (the order stays banked and drains later). */
    public static void guardHeavyStart(@NonNull Activity activity, @NonNull Runnable onProceed) {
        guardHeavyStart(activity, onProceed, () -> {});
    }

    /**
     * Three-arg form: {@code onDeclined} runs when the user declines the metered
     * prompt (or dismisses it) or when there is no network -- for callers with no
     * queue, so they can undo the UI they were about to commit.
     */
    public static void guardHeavyStart(@NonNull Activity activity, @NonNull Runnable onProceed,
                                       @NonNull Runnable onDeclined) {
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
                        .setNegative(R.string.k2go_netpolicy_not_now, onDeclined::run)
                        .setOnCancel(onDeclined::run)
                        .show();
                return;
            case BLOCKED_NO_NETWORK:
            default:
                View root = activity.findViewById(android.R.id.content);
                if (root != null) {
                    Snackbars.make(root, R.string.k2go_netpolicy_offline).show();
                }
                onDeclined.run();
        }
    }
}
