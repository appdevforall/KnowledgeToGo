package org.appdevforall.k2go.networkpolicy.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.networkpolicy.domain.NetworkClass;

/**
 * Reads the cost class off the ACTIVE DEFAULT network.
 *
 * <p>This is the single reader of ConnectivityManager for cost decisions
 * (ADR-395). The two existing internet checks -- DashboardRebuild.hasInternet and
 * InstallService.hasValidatedInternet -- should route through here as a follow-up
 * so there is one source of the "what is the network" fact, not three.
 *
 * <p>The rule is by NET_CAPABILITY_NOT_METERED, never by transport: on real
 * hardware the cellular IMS PDN reports NOT_METERED while the internet APN does
 * not (see ADR-395 device evidence). The pure mapping lives in
 * {@link NetworkClass#from(boolean, boolean)}; this class only extracts the two
 * facts from Android.
 */
public final class AndroidNetworkClassifier {

    private AndroidNetworkClassifier() {}

    @NonNull
    public static NetworkClass classify(@NonNull Context ctx) {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return NetworkClass.NONE;
        Network net = cm.getActiveNetwork();
        if (net == null) return NetworkClass.NONE;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return NetworkClass.NONE;
        boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        return NetworkClass.from(hasInternet, notMetered);
    }
}
