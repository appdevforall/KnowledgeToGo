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
    /** Box answer {@code {"open":true,"code":"4F7K"}}: is a class open on the box, and its code. */
    public static final String STATE_PATH = "/school/state";

    /** DNS-SD service type of an open class on the local network (mDNS). */
    public static final String SERVICE_TYPE = "_k2go-class._tcp";
    /**
     * The start of the service name that a teacher box asks for; the class code follows. Not
     * personal data (K2G-P5). The network makes the name unique when two boxes ask for it.
     */
    public static final String SERVICE_NAME = "K2Go class";
    /** The one TXT key of the service: the class code. */
    public static final String TXT_CODE = "code";

    // The box makes the code (school/class.ts): 4 characters, no 0/O and no 1/I.
    private static final Pattern CLASS_CODE = Pattern.compile("^[2-9A-HJ-NP-Z]{4}$");
    private static final Pattern OPEN_TRUE = Pattern.compile("\"open\"\\s*:\\s*true\\b");
    private static final Pattern OPEN_FALSE = Pattern.compile("\"open\"\\s*:\\s*false\\b");
    // A class token as the box makes it (school/class.ts), as a JSON string from the page.
    private static final Pattern TOKEN_JS = Pattern.compile("^\"([0-9a-f]{16,64})\"$");
    private static final Pattern CODE_FIELD = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]*)\"");

    // http://<host>[:port][/anything]. The host is a dotted IPv4 address or a host name.
    private static final Pattern CODE = Pattern.compile(
            "^http://([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::(\\d{1,5}))?(?:/.*)?$");
    private static final Pattern IPV4_LIKE = Pattern.compile("^[0-9.]+$");
    private static final Pattern CLASS_PAGE = Pattern.compile("^http://[^/?#]+/school/");

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

    /** True when {@code url} opens the teacher page on this phone's box. */
    public static boolean isTeacherUrl(String url, String boxBase) {
        return url != null && url.startsWith(teacherUrl(boxBase));
    }

    /**
     * The screen stays on while it shows a class page and the class is open, as during a video: a
     * student must not lose the teacher's page to the screen saver (maintainer, 2026-11-25).
     * {@code classOpen} comes from the box (the class observers of the screen); with no answer, off.
     */
    public static boolean keepScreenOn(String url, boolean classOpen) {
        return classOpen && isClassPage(url);
    }

    /** True when {@code url} is a class page (teacher or student) on a box: a path under /school/. */
    public static boolean isClassPage(String url) {
        return url != null && CLASS_PAGE.matcher(url).find();
    }

    /** The class state on the box at {@code host} (an address, or "localhost") and {@code port}. */
    public static String stateUrl(String host, int port) {
        return "http://" + host + ":" + port + STATE_PATH;
    }

    /** True for a class code as the box makes it. */
    public static boolean isClassCode(String code) {
        return code != null && CLASS_CODE.matcher(code).matches();
    }

    /**
     * The code of the open class in a box answer, or null when no class is open, the body is not
     * a box answer, or the code is not a class code.
     */
    public static String codeFromState(String body) {
        if (body == null || !OPEN_TRUE.matcher(body).find()) return null;
        Matcher m = CODE_FIELD.matcher(body);
        return m.find() && isClassCode(m.group(1)) ? m.group(1) : null;
    }

    /**
     * True when {@code body} is a class state of a box: {@code "open": false}, or an open class with
     * a class code. Another server at the address, or an answer that this app cannot read, is no
     * answer of a class box: the student screen does not end its class on it.
     */
    public static boolean isState(String body) {
        return body != null && (OPEN_FALSE.matcher(body).find() || codeFromState(body) != null);
    }

    /** The class token in a JSON string from the student page, or null (no token, or not a token). */
    public static String tokenFromJs(String json) {
        if (json == null) return null;
        Matcher m = TOKEN_JS.matcher(json.trim());
        return m.matches() ? m.group(1) : null;
    }

    /**
     * The student page URL for a class of the list, with its code in the fragment. A page on the
     * same box address can keep the token of an older class (the phone missed its end); the code
     * tells the page that this is another class, so it joins it and does not stop at "The class
     * ended". The fragment never goes to a server.
     */
    public static String withClass(String studentUrl, String code) {
        if (!isClassCode(code)) return studentUrl;
        int hash = studentUrl.indexOf('#');
        return (hash < 0 ? studentUrl : studentUrl.substring(0, hash)) + "#c=" + code;
    }

    /**
     * The student page URL with the class token of the old address, for the page to use it. The
     * token goes in the fragment: a browser never sends it to a server, so it is in no log; the
     * page removes it from the address and the history.
     */
    public static String withToken(String studentUrl, String token) {
        if (token == null) return studentUrl;
        int hash = studentUrl.indexOf('#');
        return (hash < 0 ? studentUrl : studentUrl.substring(0, hash)) + "#t=" + token;
    }

    /**
     * The student page for a scanned code, or null when the code is not a box address on
     * {@code port}. Only plain http to the box port of a private IPv4 address (10/8, 172.16/12,
     * 192.168/16) is accepted: a teacher box is on the local network, and Connect and the class
     * announcement give its IPv4 address. So a code cannot send the student WebView to another
     * site (review S7).
     */
    public static String studentUrlFromCode(String code, int port) {
        if (code == null) return null;
        Matcher m = CODE.matcher(code.trim());
        if (!m.matches()) return null;
        String host = m.group(1);
        if (m.group(2) == null || Integer.parseInt(m.group(2)) != port) return null;
        if (!isPrivateIpv4(host)) return null;
        return "http://" + host + ":" + port + STUDENT_PATH;
    }

    private static boolean isPrivateIpv4(String host) {
        if (!IPV4_LIKE.matcher(host).matches()) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            // A leading zero is octal for a browser ("010" is 8): refuse it.
            if (parts[i].isEmpty() || parts[i].length() > 3
                    || (parts[i].length() > 1 && parts[i].charAt(0) == '0')) return false;
            b[i] = Integer.parseInt(parts[i]);
            if (b[i] > 255) return false;
        }
        return b[0] == 10
                || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                || (b[0] == 192 && b[1] == 168);
    }
}
