/*
 * ============================================================================
 * Name        : BoxIdentityLine.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-549. Reads the box identity step's boot output line.
 * ============================================================================
 */
package org.appdevforall.k2go.boxidentity.domain;

/**
 * The box identity step (static/box-identity/k2go_box_identity.py) prints
 * "[k2go-box-identity] begin" once, only on a start that makes per-box values. That line is the
 * only signal that this boot prepares the system; the app keeps no flag of its own.
 */
public final class BoxIdentityLine {

    static final String BEGIN = "[k2go-box-identity] begin";

    private BoxIdentityLine() {
    }

    /** True when {@code line} is the step's "begin" line (a log prefix before it is allowed). */
    public static boolean isBegin(String line) {
        if (line == null) return false;
        String t = line.trim();
        return t.equals(BEGIN) || t.endsWith(" " + BEGIN);
    }
}
