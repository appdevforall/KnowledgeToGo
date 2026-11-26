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

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.domain.ClassFinder;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.FoundClass;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** The class services of {@link NsdServiceFinder}: one candidate per address. */
public final class NsdClassFinder implements ClassFinder {

    private final NsdServiceFinder finder;

    public NsdClassFinder(Context ctx) {
        finder = new NsdServiceFinder(ctx, ClassLinks.SERVICE_TYPE);
    }

    @Override
    public void start(Listener l) {
        finder.start(new NsdServiceFinder.Sink() {
            @Override
            public void onResolved(String name, Map<String, byte[]> txt, List<String> ipv4s, int port) {
                // The network can still give an old address next to the new one: one candidate
                // each; the box at each address confirms or not.
                byte[] v = txt.get(ClassLinks.TXT_CODE);
                String code = v == null ? null : new String(v, StandardCharsets.US_ASCII);
                for (String address : ipv4s) {
                    FoundClass c = FoundClass.of(name, code, address, port, BoxEndpoints.PORT);
                    if (c != null) l.onFound(c);
                }
            }

            @Override
            public void onLost(String name) {
                l.onLost(name);
            }
        });
    }

    @Override
    public void stop() {
        finder.stop();
    }
}
