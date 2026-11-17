/*
 * ============================================================================
 * Name        : WifiQr.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-577. The one builder of the Wi-Fi auto-join QR payload, for Connect, Clone,
 *               Content Exchange Share and the QR screen. Escapes the reserved characters, so a
 *               name or password with ';' or ':' still joins. Pure JVM (no Android).
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

/** The "WIFI:" join payload (ZXing Wi-Fi network config format). */
public final class WifiQr {

    private WifiQr() {
    }

    /** WPA network [ssid] with [pass]; an empty or null [pass] makes an open network. */
    public static String payload(String ssid, String pass) {
        if (pass == null || pass.isEmpty()) {
            return "WIFI:T:nopass;S:" + escape(ssid) + ";;";
        }
        return "WIFI:T:WPA;S:" + escape(ssid) + ";P:" + escape(pass) + ";;";
    }

    static String escape(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
