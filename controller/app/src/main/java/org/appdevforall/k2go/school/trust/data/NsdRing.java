/*
 * ============================================================================
 * Name        : NsdRing.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: announces and finds setup rings on the Wi-Fi (mDNS).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.data.NsdServiceFinder;
import org.appdevforall.k2go.school.trust.domain.SchoolRing;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The ring goes on the network with the school id, the school name and the kind of ring: no device
 * or person data (K2G-P5). The system mDNS service sends it: no new permission.
 */
public final class NsdRing {

    private static final String TAG = "K2GoSchool";
    static final String SERVICE_TYPE = "_k2go-ring._tcp";
    private static final String TXT_KIND = "kind";
    private static final String TXT_SCHOOL = "school";
    private static final String TXT_NAME = "name";
    private static final Pattern SCHOOL = Pattern.compile("[0-9a-f]{16}");

    /** Results on the main thread. */
    public interface Listener {
        void onFound(SchoolRing.Found ring);
        void onLost(String service);
    }

    private final NsdManager nsd;
    private final NsdServiceFinder finder;
    // Main thread only.
    private NsdManager.RegistrationListener registered;

    public NsdRing(Context ctx) {
        nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        finder = new NsdServiceFinder(ctx, SERVICE_TYPE);
    }

    public void announce(SchoolRing.Kind kind, String school, String schoolName) {
        stopAnnounce();
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("K2Go school " + school.substring(0, 4) + (kind == SchoolRing.Kind.TEACHER ? " T" : " S"));
        info.setServiceType(SERVICE_TYPE);
        info.setPort(BoxEndpoints.PORT);
        info.setAttribute(TXT_KIND, kind == SchoolRing.Kind.TEACHER ? "teacher" : "student");
        info.setAttribute(TXT_SCHOOL, school);
        info.setAttribute(TXT_NAME, schoolName);
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) { Log.i(TAG, "ring announced"); }
            @Override public void onRegistrationFailed(NsdServiceInfo s, int code) { Log.w(TAG, "ring announce failed: " + code); }
            @Override public void onServiceUnregistered(NsdServiceInfo s) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int code) { }
        };
        registered = l;
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (RuntimeException e) {
            Log.w(TAG, "ring announce refused", e);
            registered = null;
        }
    }

    public void stopAnnounce() {
        NsdManager.RegistrationListener l = registered;
        registered = null;
        if (l == null) return;
        try {
            nsd.unregisterService(l);
        } catch (RuntimeException ignored) {
            // the system no longer knows this listener: it is off
        }
    }

    public void find(Listener l) {
        finder.start(new NsdServiceFinder.Sink() {
            @Override
            public void onResolved(String name, Map<String, byte[]> txt, List<String> ipv4s, int port) {
                String kind = text(txt, TXT_KIND);
                String school = text(txt, TXT_SCHOOL);
                String schoolName = text(txt, TXT_NAME);
                if (school == null || !SCHOOL.matcher(school).matches() || schoolName == null || schoolName.length() > 80) return;
                SchoolRing.Kind k = "teacher".equals(kind) ? SchoolRing.Kind.TEACHER
                        : "student".equals(kind) ? SchoolRing.Kind.STUDENT : null;
                if (k == null) return;
                // The ring routes are on the box port, as the class pages.
                for (String a : ipv4s) l.onFound(new SchoolRing.Found(name, k, school, schoolName, a, BoxEndpoints.PORT));
            }

            @Override
            public void onLost(String name) {
                l.onLost(name);
            }
        });
    }

    public void stopFind() {
        finder.stop();
    }

    private static String text(Map<String, byte[]> txt, String key) {
        byte[] v = txt.get(key);
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }
}
