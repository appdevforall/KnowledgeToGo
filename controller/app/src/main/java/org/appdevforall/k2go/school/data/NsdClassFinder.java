/*
 * ============================================================================
 * Name        : NsdClassFinder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: finds the open classes on the Wi-Fi with Android NSD (mDNS).
 * ============================================================================
 */
package org.appdevforall.k2go.school.data;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.domain.ClassFinder;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.FoundClass;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Discovery and resolve run in the system mDNS service: no multicast lock and no new permission.
 * Before Android 14 the system resolves one service at a time for an app, so the resolves go in a
 * queue. A resolve has no time-out in the system (a service can leave while it runs), so the queue
 * frees its slot after {@link #RESOLVE_TIMEOUT_MS}. All state is on the main thread; the NSD
 * callbacks post to it.
 */
public final class NsdClassFinder implements ClassFinder {

    private static final String TAG = "K2GoSchool";
    private static final long RETRY_MS = 500;
    private static final long RESOLVE_TIMEOUT_MS = 5000;
    // FAILURE_ALREADY_ACTIVE retries of one service (about 5 s), then it waits for the next search.
    private static final int MAX_TRIES = 10;

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;
    private NsdManager.DiscoveryListener discovery;
    // A callback of an older start or of a stopped search sees another generation and does nothing.
    private int generation;
    // Services on the network now, by name. A resolve that ends after a "lost" adds nothing.
    private final Set<String> present = new HashSet<>();
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private final Map<String, Integer> tries = new HashMap<>();
    // The resolve that has the slot now, or null. A late result of another resolve frees nothing.
    private NsdManager.ResolveListener resolving;
    private Runnable resolveTimeout;

    public NsdClassFinder(Context ctx) {
        nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public void start(Listener l) {
        if (discovery != null) return;
        listener = l;
        final int gen = ++generation;
        NsdManager.DiscoveryListener d = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int code) {
                Log.w(TAG, "class search failed: " + code);
                main.post(() -> { if (gen == generation && discovery == this) discovery = null; });
            }
            @Override public void onStopDiscoveryFailed(String type, int code) {}
            @Override public void onServiceFound(NsdServiceInfo s) {
                main.post(() -> {
                    if (gen != generation) return;
                    present.add(s.getServiceName());
                    toResolve.add(s);
                    resolveNext(gen);
                });
            }
            @Override public void onServiceLost(NsdServiceInfo s) {
                main.post(() -> {
                    if (gen != generation) return;
                    String name = s.getServiceName();
                    present.remove(name);
                    tries.remove(name);
                    for (Iterator<NsdServiceInfo> it = toResolve.iterator(); it.hasNext(); ) {
                        if (name.equals(it.next().getServiceName())) it.remove();
                    }
                    listener.onLost(name);
                });
            }
        };
        discovery = d;
        try {
            nsd.discoverServices(ClassLinks.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, d);
        } catch (RuntimeException e) {
            Log.w(TAG, "class search refused", e);
            discovery = null;
        }
    }

    @Override
    public void stop() {
        generation++;   // the posted callbacks stay: they see the new generation and only free the queue
        present.clear();
        toResolve.clear();
        tries.clear();
        listener = null;
        // A resolve that runs now keeps the slot until its result or its time-out, which see the
        // new generation and only free the slot.
        NsdManager.DiscoveryListener d = discovery;
        discovery = null;
        if (d == null) return;
        try {
            nsd.stopServiceDiscovery(d);
        } catch (RuntimeException e) {
            Log.w(TAG, "class search stop refused", e);
        }
    }

    @SuppressWarnings("deprecation")   // resolveService: deprecated in API 34, the only API before it
    private void resolveNext(int gen) {
        if (resolving != null || gen != generation) return;
        NsdServiceInfo s = toResolve.poll();
        if (s == null) return;
        final String name = s.getServiceName();
        NsdManager.ResolveListener r = new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int code) {
                main.post(() -> {
                    boolean mine = free(this);
                    if (gen == generation && code == NsdManager.FAILURE_ALREADY_ACTIVE
                            && present.contains(name) && retry(name)) {
                        toResolve.addFirst(s);   // the slot of the system is not free yet
                        main.postDelayed(() -> resolveNext(gen), RETRY_MS);
                        return;
                    }
                    Log.w(TAG, "class resolve failed: " + code);
                    if (mine) resolveNext(generation);
                });
            }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                main.post(() -> {
                    boolean mine = free(this);
                    if (gen == generation && present.contains(name)) {
                        tries.remove(name);
                        FoundClass c = FoundClass.of(name, ipv4(info), info.getPort(), BoxEndpoints.PORT);
                        if (c != null) listener.onFound(c);
                    }
                    if (mine) resolveNext(generation);
                });
            }
        };
        resolving = r;
        resolveTimeout = () -> {
            if (resolving != r) return;
            Log.w(TAG, "class resolve timed out");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try { nsd.stopServiceResolution(r); } catch (RuntimeException ignored) { }
            }
            resolving = null;
            resolveNext(generation);
        };
        main.postDelayed(resolveTimeout, RESOLVE_TIMEOUT_MS);
        try {
            nsd.resolveService(s, r);
        } catch (RuntimeException e) {
            Log.w(TAG, "class resolve refused", e);
            free(r);
            main.post(() -> resolveNext(generation));
        }
    }

    /** True when {@code r} had the slot; the slot is then free. */
    private boolean free(NsdManager.ResolveListener r) {
        if (resolving != r) return false;
        resolving = null;
        main.removeCallbacks(resolveTimeout);
        return true;
    }

    private boolean retry(String name) {
        Integer n = tries.get(name);
        int next = n == null ? 1 : n + 1;
        tries.put(name, next);
        return next <= MAX_TRIES;
    }

    /** The student page needs an IPv4 address (the scanned code rules); IPv6 only is not a class. */
    private static String ipv4(NsdServiceInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (InetAddress a : info.getHostAddresses()) {
                if (a instanceof Inet4Address) return a.getHostAddress();
            }
            return null;
        }
        InetAddress h = info.getHost();
        return h instanceof Inet4Address ? h.getHostAddress() : null;
    }
}
