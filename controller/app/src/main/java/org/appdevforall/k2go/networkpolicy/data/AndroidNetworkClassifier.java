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
 * <p>The single reader of ConnectivityManager (ADR-395 / K2GO-404): {@link #classify} for cost,
 * {@link #hasInternet} / {@link #hasValidatedInternet} for the checks that used to live in
 * DashboardRebuild and InstallService. One source for "what is the network".
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

    /** The single "is there internet" reader (K2GO-404: folds DashboardRebuild.hasInternet). A null
     *  ConnectivityManager reads as no internet (was "unknown -> true"); that edge is never hit. */
    public static boolean hasInternet(@NonNull Context ctx) {
        return classify(ctx) != NetworkClass.NONE;
    }

    /** Like {@link #hasInternet} but also requires NET_CAPABILITY_VALIDATED: a captive-portal
     *  association has INTERNET but not VALIDATED, and resuming a download onto it soft-fails
     *  (K2GO-404: folds InstallService.hasValidatedInternet). */
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
