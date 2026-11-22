package org.appdevforall.k2go.auth.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.appdevforall.k2go.auth.domain.PasswordRepository.Failure;
import org.appdevforall.k2go.auth.domain.PasswordState;
import org.json.JSONObject;
import org.junit.Test;

/** The wire contract with dash-node sockets/passwords.ts (K2GO-552). */
public class PasswordRepositoryImplTest {

    private static Failure http(int status, String code) {
        return PasswordRepositoryImpl.failureOf(new CredentialsClient.HttpError(status, code));
    }

    @Test public void errorCodesWinOverTheStatus() {
        assertEquals(Failure.WEAK_PASSWORD, http(400, "weak_password"));
        assertEquals(Failure.MISMATCH, http(409, "mismatch"));
        assertEquals(Failure.BUSY, http(409, "busy"));
    }

    @Test public void statusFallbacks() {
        assertEquals(Failure.REJECTED, http(401, "Calibre-Web rejected these credentials"));
        assertEquals(Failure.REJECTED, http(403, ""));
        assertEquals(Failure.UNREACHABLE, http(503, "unreachable"));
        assertEquals(Failure.FAILED, http(500, "set_failed"));
        assertEquals(Failure.UNREACHABLE, PasswordRepositoryImpl.failureOf(new java.io.IOException("refused")));
    }

    @Test public void parsesTheStatus() throws Exception {
        PasswordState s = PasswordRepositoryImpl.parseState("kolibri",
                new JSONObject("{\"service\":\"kolibri\",\"state\":\"ok\",\"factoryDefault\":true}"));
        assertEquals(PasswordState.Sync.OK, s.sync);
        assertEquals(Boolean.TRUE, s.factoryDefault);

        s = PasswordRepositoryImpl.parseState("calibre",
                new JSONObject("{\"state\":\"mismatch\",\"factoryDefault\":null}"));
        assertEquals(PasswordState.Sync.MISMATCH, s.sync);
        assertNull(s.factoryDefault);

        s = PasswordRepositoryImpl.parseState("forgejo", new JSONObject("{\"state\":\"unreachable\"}"));
        assertEquals(PasswordState.Sync.UNREACHABLE, s.sync);

        s = PasswordRepositoryImpl.parseState("forgejo",
                new JSONObject("{\"state\":\"no_account\",\"factoryDefault\":null}"));
        assertEquals(PasswordState.Sync.NO_ACCOUNT, s.sync);
        assertNull(s.factoryDefault);

        s = PasswordRepositoryImpl.parseState("forgejo",
                new JSONObject("{\"state\":\"ok\",\"factoryDefault\":false,\"minLength\":8}"));
        assertEquals(8, s.minLength);
        assertEquals(0, PasswordRepositoryImpl.parseState("kolibri",
                new JSONObject("{\"state\":\"ok\"}")).minLength);   // an older box: not known
    }
}
