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
 * <p>This is the single reader of ConnectivityManager (ADR-395 / K2GO-404). The
 * two former internet checks -- DashboardRebuild.hasInternet and
 * InstallService.hasValidatedInternet -- now route through {@link #hasInternet}
 * and {@link #hasValidatedInternet} here, so there is one source of the "what is
 * the network" fact, not three.
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

    /**
     * True when the active default network is internet-capable. The one plain "is there internet"
     * reader (K2GO-404: folds {@code DashboardRebuild.hasInternet}). Unlike the former reader, an
     * absent ConnectivityManager reads as no internet (via {@link #classify} returning NONE) rather
     * than "unknown -> true"; that edge is effectively never hit and failing closed on it is safe.
     */
    public static boolean hasInternet(@NonNull Context ctx) {
        return classify(ctx) != NetworkClass.NONE;
    }

    /**
     * True when the active default network is internet-capable AND Android has VALIDATED real
     * connectivity. Distinct from {@link #hasInternet}: a captive-portal association reports
     * INTERNET but not VALIDATED, and resuming a download onto it just soft-fails. The install
     * resume path requires this stricter check (K2GO-404: folds
     * {@code InstallService.hasValidatedInternet}, preserving the VALIDATED requirement).
     */
    public static boolean hasValidatedInternet(@NonNull Context ctx) {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
