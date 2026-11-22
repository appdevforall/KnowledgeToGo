package org.appdevforall.k2go.auth.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AuthNoticeTest {

    private static PasswordState ok(String s, Boolean factory) {
        return new PasswordState(s, PasswordState.Sync.OK, factory);
    }

    @Test public void factoryPasswordsAreNamedInOrder() {
        AuthNotice n = AuthNotice.decide(Arrays.asList(ok("calibre", true), ok("kolibri", false),
                ok("forgejo", true)), false);
        assertEquals(AuthNotice.Kind.FACTORY_PASSWORD, n.kind);
        assertEquals(Arrays.asList("calibre", "forgejo"), n.services);
    }

    @Test public void remindersOffHidesTheFactoryNotice() {
        AuthNotice n = AuthNotice.decide(Collections.singletonList(ok("calibre", true)), true);
        assertEquals(AuthNotice.Kind.NONE, n.kind);
    }

    @Test public void mismatchComesFirstAndIgnoresTheReminderSwitch() {
        AuthNotice n = AuthNotice.decide(Arrays.asList(ok("calibre", true),
                new PasswordState("kolibri", PasswordState.Sync.MISMATCH, null)), true);
        assertEquals(AuthNotice.Kind.MISMATCH, n.kind);
        assertEquals(Collections.singletonList("kolibri"), n.services);
    }

    @Test public void unknownOrUnreachableShowsNothing() {
        AuthNotice n = AuthNotice.decide(Arrays.asList(ok("calibre", null),
                new PasswordState("forgejo", PasswordState.Sync.UNREACHABLE, null)), false);
        assertEquals(AuthNotice.Kind.NONE, n.kind);
    }

    @Test public void onlyOkOrMismatchHasAnAccount() {
        assertEquals(true, new PasswordState("calibre", PasswordState.Sync.OK, true).hasAccount());
        assertEquals(true, new PasswordState("calibre", PasswordState.Sync.MISMATCH, null).hasAccount());
        assertEquals(false, new PasswordState("forgejo", PasswordState.Sync.NO_ACCOUNT, null).hasAccount());
        assertEquals(false, new PasswordState("kolibri", PasswordState.Sync.UNREACHABLE, null).hasAccount());
    }

    @Test public void noAdminAccountShowsNothing() {
        AuthNotice n = AuthNotice.decide(Arrays.asList(
                new PasswordState("forgejo", PasswordState.Sync.NO_ACCOUNT, null)), false);
        assertEquals(AuthNotice.Kind.NONE, n.kind);
    }
}
