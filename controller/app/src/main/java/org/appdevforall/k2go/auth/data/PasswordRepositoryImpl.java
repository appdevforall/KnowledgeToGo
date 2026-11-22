/*
 * ============================================================================
 * Name        : PasswordRepositoryImpl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. PasswordRepository over the dash-node REST API (CredentialsClient).
 *               Callbacks run on the main thread.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.data;

import android.os.Handler;
import android.os.Looper;

import org.appdevforall.k2go.auth.domain.PasswordRepository;
import org.appdevforall.k2go.auth.domain.PasswordState;
import org.appdevforall.k2go.util.AppExecutors;
import org.json.JSONObject;

public final class PasswordRepositoryImpl implements PasswordRepository {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private interface Call<T> { T run() throws Exception; }

    private static <T> void async(Call<T> call, Callback<T> cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                T v = call.run();
                MAIN.post(() -> cb.onResult(v));
            } catch (Exception e) {
                Failure f = failureOf(e);
                MAIN.post(() -> cb.onFailure(f));
            }
        });
    }

    /** Maps the box's error answer (sockets/passwords.ts and the POST /credentials route). */
    static Failure failureOf(Exception e) {
        if (!(e instanceof CredentialsClient.HttpError)) return Failure.UNREACHABLE;
        CredentialsClient.HttpError h = (CredentialsClient.HttpError) e;
        switch (h.code) {
            case "weak_password": return Failure.WEAK_PASSWORD;
            case "mismatch": return Failure.MISMATCH;
            case "busy": return Failure.BUSY;
            default: break;
        }
        if (h.status == 401 || h.status == 403) return Failure.REJECTED;
        if (h.status == 503) return Failure.UNREACHABLE;
        return Failure.FAILED;
    }

    static PasswordState parseState(String service, JSONObject o) {
        PasswordState.Sync sync;
        switch (o.optString("state", "")) {
            case "ok": sync = PasswordState.Sync.OK; break;
            case "mismatch": sync = PasswordState.Sync.MISMATCH; break;
            case "no_account": sync = PasswordState.Sync.NO_ACCOUNT; break;
            default: sync = PasswordState.Sync.UNREACHABLE; break;
        }
        Boolean factory = o.isNull("factoryDefault") ? null : o.optBoolean("factoryDefault");
        return new PasswordState(service, sync, factory);
    }

    @Override
    public void status(String service, Callback<PasswordState> cb) {
        async(() -> parseState(service,
                CredentialsClient.request("GET", CredentialsClient.url(service) + "/status", null)), cb);
    }

    @Override
    public void change(String service, String newPassword, Callback<Boolean> cb) {
        async(() -> CredentialsClient.request("POST", CredentialsClient.url(service) + "/password",
                new JSONObject().put("password", newPassword)).optBoolean("verified", false), cb);
    }

    @Override
    public void reset(String service, Callback<Boolean> cb) {
        async(() -> CredentialsClient.request("POST", CredentialsClient.url(service) + "/reset",
                new JSONObject()).optBoolean("verified", false), cb);
    }

    @Override
    public void repair(String service, String username, String password, Callback<Boolean> cb) {
        async(() -> CredentialsClient.request("POST", CredentialsClient.url(service),
                new JSONObject().put("username", username).put("password", password))
                .optBoolean("verified", false), cb);
    }
}
