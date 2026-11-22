/*
 * ============================================================================
 * Name        : AuthNotice.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. Which notice the app shows at start. A mismatch breaks auto-login, so it
 *               always shows and comes first. The factory-password risk shows unless the user
 *               turned those reminders off.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AuthNotice {

    public enum Kind { NONE, MISMATCH, FACTORY_PASSWORD }

    public final Kind kind;
    /** The services the notice names, in the order given. */
    public final List<String> services;

    private AuthNotice(Kind kind, List<String> services) {
        this.kind = kind;
        this.services = Collections.unmodifiableList(services);
    }

    public static AuthNotice decide(List<PasswordState> states, boolean factoryRemindersOff) {
        List<String> mismatch = new ArrayList<>();
        List<String> factory = new ArrayList<>();
        for (PasswordState s : states) {
            if (s.sync == PasswordState.Sync.MISMATCH) mismatch.add(s.service);
            else if (s.usesFactoryPassword()) factory.add(s.service);
        }
        if (!mismatch.isEmpty()) return new AuthNotice(Kind.MISMATCH, mismatch);
        if (!factory.isEmpty() && !factoryRemindersOff) return new AuthNotice(Kind.FACTORY_PASSWORD, factory);
        return new AuthNotice(Kind.NONE, new ArrayList<>());
    }
}
