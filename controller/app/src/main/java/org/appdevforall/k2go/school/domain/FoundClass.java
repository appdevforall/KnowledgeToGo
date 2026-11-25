/*
 * ============================================================================
 * Name        : FoundClass.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: a class that a teacher box announces on the local network.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/** One open class on the Wi-Fi: its code, the box address and the student page on that box. */
public final class FoundClass {

    /** The network service name. Unique on the network, so it identifies the entry in the list. */
    public final String key;
    /** The class code that the box made; the teacher page shows it too. */
    public final String code;
    /** The IPv4 address of the teacher box. */
    public final String address;
    public final String studentUrl;

    public FoundClass(String key, String code, String address, String studentUrl) {
        this.key = key;
        this.code = code;
        this.address = address;
        this.studentUrl = studentUrl;
    }

    /**
     * The class for a resolved network service, or null when the code is not a class code, or the
     * address or port is not a box address on {@code boxPort}. The address gets the same check as
     * a scanned code, so a service cannot send the student page to another site.
     */
    public static FoundClass of(String key, String code, String hostAddress, int port, int boxPort) {
        if (key == null || hostAddress == null || !ClassLinks.isClassCode(code)) return null;
        String url = ClassLinks.studentUrlFromCode("http://" + hostAddress + ":" + port, boxPort);
        return url == null ? null : new FoundClass(key, code, hostAddress, url);
    }
}
