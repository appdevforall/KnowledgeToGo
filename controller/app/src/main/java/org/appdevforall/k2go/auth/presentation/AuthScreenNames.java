/*
 * ============================================================================
 * Name        : AuthScreenNames.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. The screen name of each managed service ("Read a book" for Calibre-Web),
 *               for Settings > Authentication and the start notice. One place, so both say the same.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.presentation;

import androidx.annotation.StringRes;

import org.appdevforall.k2go.R;

public final class AuthScreenNames {
    private AuthScreenNames() {}

    /** The screen name of a service id from AuthServices.ALL (K2GO-417 added Forgejo). */
    @StringRes
    public static int of(String id) {
        switch (id) {
            case "calibre": return R.string.k2go_auth_svc_books;
            case "kolibri": return R.string.k2go_auth_svc_courses;
            default: return R.string.k2go_card_forgejo;
        }
    }
}
