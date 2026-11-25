/*
 * ============================================================================
 * Name        : NsdClassAdvertiser.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: announces the open class on the Wi-Fi with Android NSD (mDNS).
 * ============================================================================
 */
package org.appdevforall.k2go.school.data;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.domain.ClassAdvertiser;
import org.appdevforall.k2go.school.domain.ClassLinks;

/**
 * The system mDNS service sends the announcement, so the app needs no multicast lock and no new
 * permission. If the app process dies, the system removes the registration.
 */
public final class NsdClassAdvertiser implements ClassAdvertiser {

    private static final String TAG = "K2GoSchool";

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Main thread only. The listener of the registration that is on the network or starting.
    private NsdManager.RegistrationListener current;
    private Callback callback;

    public NsdClassAdvertiser(Context ctx) {
        nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public void register(String code, Callback cb) {
        callback = cb;
        NsdServiceInfo info = new NsdServiceInfo();
        // Only the random class code goes on the network: no device or person data (K2G-P5).
        info.setServiceName(ClassLinks.SERVICE_NAME + " " + code);
        info.setAttribute(ClassLinks.TXT_CODE, code);
        info.setServiceType(ClassLinks.SERVICE_TYPE);
        info.setPort(BoxEndpoints.PORT);
        // NsdManager refuses a listener that is still in use: one listener per registration.
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "class announced as " + s.getServiceName());
                main.post(() -> cb.done(true));
            }
            @Override public void onRegistrationFailed(NsdServiceInfo s, int code) {
                Log.w(TAG, "class announce failed: " + code);
                main.post(() -> { if (current == this) current = null; cb.failed(); });
            }
            @Override public void onServiceUnregistered(NsdServiceInfo s) {
                Log.i(TAG, "class announce removed");
                main.post(() -> { if (current == this) current = null; cb.done(false); });
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int code) {
                Log.w(TAG, "class announce remove failed: " + code);
                main.post(cb::failed);
            }
        };
        current = l;
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (RuntimeException e) {
            Log.w(TAG, "class announce refused", e);
            current = null;
            main.post(cb::failed);
        }
    }

    @Override
    public void unregister() {
        NsdManager.RegistrationListener l = current;
        Callback cb = callback;
        if (l == null) {
            if (cb != null) main.post(() -> cb.done(false));
            return;
        }
        try {
            nsd.unregisterService(l);
        } catch (RuntimeException e) {   // the system no longer knows this listener: it is off
            current = null;
            if (cb != null) main.post(() -> cb.done(false));
        }
    }
}
