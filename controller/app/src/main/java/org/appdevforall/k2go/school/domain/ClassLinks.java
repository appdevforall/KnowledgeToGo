/*
 * ============================================================================
 * Name        : ClassLinks.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the class page addresses, and the check of a scanned teacher code.
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules for the class entry. The class pages are served by the teacher box (dash-node, routes
 * under /school/). The teacher opens them on its own box; a student scans the "Open" code of the
 * teacher's Connect screen ({@code http://<address>:8085}) and opens the student page there.
 */
public final class ClassLinks {

    public static final String TEACHER_PATH = "/school/teacher/";
    public static final String STUDENT_PATH = "/school/student.html";

    // http://<host>[:port][/anything]. The host is a dotted IPv4 address or a host name.
    private static final Pattern CODE = Pattern.compile(
            "^http://([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::(\\d{1,5}))?(?:/.*)?$");
    private static final Pattern IPV4_LIKE = Pattern.compile("^[0-9.]+$");

    /** HTTP status value for "the box did not answer" (no connection, time-out). */
    public static final int NO_ANSWER = -1;

    /** What this phone's box can do for the teacher, from one GET of the teacher page. */
    public enum Teach {
        /** The box serves the teacher page. */
        AVAILABLE,
        /** The box answers, but it has no class module. */
        NO_MODULE,
        /** The box does not answer, or it answers with a server error. */
        NO_BOX
    }

    private ClassLinks() {}

    /**
     * The teacher state for the HTTP status of a GET of the teacher page, without redirects.
     * Only 200 is the page. A 5xx is a box that has the route but cannot serve it now.
     */
    public static Teach teachFromStatus(int status) {
        if (status == 200) return Teach.AVAILABLE;
        if (status == NO_ANSWER || status >= 500) return Teach.NO_BOX;
        return Teach.NO_MODULE;
    }

    /** The teacher page on this phone's box. {@code boxBase} is scheme + host + port, no slash. */
    public static String teacherUrl(String boxBase) {
        return boxBase + TEACHER_PATH;
    }

    /**
     * The student page for a scanned code, or null when the code is not a box address on
     * {@code port}. Only plain http to the box port is accepted, so a code cannot send the
     * student WebView to another site.
     */
    public static String studentUrlFromCode(String code, int port) {
        if (code == null) return null;
        Matcher m = CODE.matcher(code.trim());
        if (!m.matches()) return null;
        String host = m.group(1);
        if (m.group(2) == null || Integer.parseInt(m.group(2)) != port) return null;
        if (IPV4_LIKE.matcher(host).matches() && !isIpv4(host)) return null;
        return "http://" + host + ":" + port + STUDENT_PATH;
    }

    private static boolean isIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }
}
