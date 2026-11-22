/*
 * ============================================================================
 * Name        : PasswordRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. Port to the box admin passwords (Kolibri, Calibre-Web, Forgejo). The box
 *               changes the password inside the service and keeps its stored copy in step.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.domain;

public interface PasswordRepository {

    /** Why a request failed, as the box reports it. */
    enum Failure { WEAK_PASSWORD, MISMATCH, BUSY, UNREACHABLE, REJECTED, FAILED }

    interface Callback<T> {
        void onResult(T value);
        void onFailure(Failure failure);
    }

    void status(String service, Callback<PasswordState> cb);

    /** Sets a new password. The result is TRUE when the service confirmed the new sign-in. */
    void change(String service, String newPassword, Callback<Boolean> cb);

    /** Puts the factory password back. The result is TRUE when the service confirmed it. */
    void reset(String service, Callback<Boolean> cb);

    /** Stores the password the service uses now (changed outside the app). The box checks it first. */
    void repair(String service, String username, String password, Callback<Boolean> cb);
}
