package org.appdevforall.k2go.networkpolicy.presentation;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.networkpolicy.data.AndroidNetworkClassifier;
import org.appdevforall.k2go.networkpolicy.data.SessionMeteredConsentStore;
import org.appdevforall.k2go.networkpolicy.domain.MeteredConsentStore;
import org.appdevforall.k2go.networkpolicy.domain.NetworkClass;
import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicy;
import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicyDecision;
import org.appdevforall.k2go.ui.dialog.BrandDialog;
import org.appdevforall.k2go.util.Snackbars;

/**
 * The single consult point before any heavy download starts (ADR-395). A caller
 * wraps its existing start call:
 *
 * <pre>NetworkPolicyGate.guardHeavyStart(activity, () -&gt; a.startZimDownload());</pre>
 *
 * <p>Stateless, like {@code OpReturnNavigator}: it owns no "is metered" flag. It
 * reads the live class off {@link AndroidNetworkClassifier} and the session
 * consent off {@link SessionMeteredConsentStore}, applies the pure
 * {@link NetworkPolicy}, and either proceeds, asks, or blocks.
 *
 * <p>It gates the START only. It does NOT control a transfer already in flight --
 * the content bytes are pulled by the in-proot server over the device default
 * network, which Android gives the app no handle to throttle (ADR-395). Callers
 * put this at the user's commit point (the Download button), never on the
 * background drain that re-hands an already-authorized wishlist.
 */
public final class NetworkPolicyGate {

    private NetworkPolicyGate() {}

    private static final NetworkPolicy POLICY = new NetworkPolicy();

    public static void guardHeavyStart(@NonNull Activity activity, @NonNull Runnable onProceed) {
        MeteredConsentStore consent = SessionMeteredConsentStore.get();
        NetworkClass net = AndroidNetworkClassifier.classify(activity);
        NetworkPolicyDecision decision = POLICY.decideHeavyStart(net, consent.isGranted());
        switch (decision) {
            case ALLOW:
                onProceed.run();
                return;
            case NEEDS_CONSENT:
                new BrandDialog(activity)
                        .setTitle(R.string.k2go_netpolicy_metered_title)
                        .setMessage(R.string.k2go_netpolicy_metered_msg)
                        .setPositive(R.string.k2go_netpolicy_continue, () -> {
                            consent.grant();
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
