package org.appdevforall.k2go.networkpolicy.presentation;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.delivery.data.OnlineConsent;
import org.appdevforall.k2go.networkpolicy.data.NetworkCostAdmission;
import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicyDecision;
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

    /**
     * K2GO-588: a control that needs the internet looks grey while the app is kept offline, but it
     * stays enabled: a tap goes through the gate, which offers to allow it for this action only.
     */
    public static void markNeedsInternet(@Nullable View control) {
        if (control == null) return;
        control.setAlpha(OnlineConsent.isEnabled(control.getContext()) ? 1f : 0.5f);
    }

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
        guard(activity, onProceed, onDeclined, onDeclined, true);
    }

    /**
     * K2GO-588: for a caller that banks its order before the gate. {@code onKeptOffline} runs when
     * the user keeps the app offline, so the caller takes the order back: a banked order drains on a
     * later pass, and that pass must not connect for an order the user did not allow.
     */
    public static void guardOrder(@NonNull Activity activity, @NonNull Runnable onProceed,
                                  @NonNull Runnable onDeclined, @NonNull Runnable onKeptOffline) {
        guard(activity, onProceed, onDeclined, onKeptOffline, true);
    }

    /**
     * K2GO-580: the same prompt for one action only (a catalog refresh, an update check, a map size
     * estimate). "Continue" does NOT grant the session consent, so it never releases the banked
     * download queue onto mobile data.
     */
    public static void guardThisAction(@NonNull Activity activity, @NonNull Runnable onProceed,
                                       @NonNull Runnable onDeclined) {
        guard(activity, onProceed, onDeclined, onDeclined, false);
    }

    private static void guard(@NonNull Activity activity, @NonNull Runnable onProceed,
                              @NonNull Runnable onDeclined, @NonNull Runnable onKeptOffline,
                              boolean grantSession) {
        NetworkPolicyDecision decision = NetworkCostAdmission.decideNow(activity);
        // K2GO-588: with Keep offline, the user allows the internet for this one action only. Nothing
        // is stored, so the next action asks again. No network: the offline snackbar is enough.
        if (decision != NetworkPolicyDecision.BLOCKED_NO_NETWORK && !OnlineConsent.isEnabled(activity)) {
            new BrandDialog(activity)
                    .setTitle(R.string.k2go_offline_action_title)
                    .setMessage(R.string.k2go_offline_action_msg)
                    .setPositive(R.string.analytics_enroll_decline, onKeptOffline::run)
                    .setNegative(R.string.k2go_offline_action_allow_once,
                            () -> decide(activity, NetworkCostAdmission.decideNow(activity),
                                    onProceed, onDeclined, grantSession))
                    .setOnCancel(onKeptOffline::run)
                    .show();
            return;
        }
        decide(activity, decision, onProceed, onDeclined, grantSession);
    }

    private static void decide(@NonNull Activity activity, @NonNull NetworkPolicyDecision decision,
                               @NonNull Runnable onProceed, @NonNull Runnable onDeclined,
                               boolean grantSession) {
        switch (decision) {
            case ALLOW:
                onProceed.run();
                return;
            case NEEDS_CONSENT:
                new BrandDialog(activity)
                        .setTitle(R.string.k2go_netpolicy_metered_title)
                        .setMessage(R.string.k2go_netpolicy_metered_msg)
                        .setPositive(R.string.k2go_netpolicy_continue, () -> {
                            if (grantSession) SessionMeteredConsentStore.get().grant();
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
