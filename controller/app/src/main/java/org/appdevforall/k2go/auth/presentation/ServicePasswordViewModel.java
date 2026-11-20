/*
 * ============================================================================
 * Name        : ServicePasswordViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. State of the Settings > Authentication screen of one service: the live
 *               password state, a running request, and the last result to show.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.presentation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.auth.data.PasswordRepositoryImpl;
import org.appdevforall.k2go.auth.domain.PasswordRepository;
import org.appdevforall.k2go.auth.domain.PasswordState;

public class ServicePasswordViewModel extends ViewModel {

    /** The result line under the forms. */
    public enum Result {
        NONE, LOAD_FAILED, NOT_SAME, CHANGED, CHANGED_UNVERIFIED, RESET_DONE, RESET_UNVERIFIED, REPAIRED, REPAIRED_UNVERIFIED,
        WEAK_PASSWORD, MISMATCH, BUSY, UNREACHABLE, REJECTED, FAILED
    }

    public static final class UiState {
        /** null while the first status check runs. */
        @Nullable public final PasswordState state;
        public final boolean busy;
        public final Result result;

        UiState(@Nullable PasswordState state, boolean busy, Result result) {
            this.state = state;
            this.busy = busy;
            this.result = result;
        }
    }

    private final PasswordRepository repo;
    private final String service;
    private final MutableLiveData<UiState> ui = new MutableLiveData<>(new UiState(null, true, Result.NONE));

    ServicePasswordViewModel(PasswordRepository repo, String service) {
        this.repo = repo;
        this.service = service;
        load();
    }

    public LiveData<UiState> ui() { return ui; }

    private UiState now() { return ui.getValue(); }

    private void post(@Nullable PasswordState state, boolean busy, Result result) {
        ui.setValue(new UiState(state, busy, result));
    }

    /** Checks the state again and keeps {@code result} on screen. */
    private void reload(Result result) {
        post(now().state, true, result);
        repo.status(service, new PasswordRepository.Callback<PasswordState>() {
            @Override public void onResult(PasswordState s) { post(s, false, result); }
            @Override public void onFailure(PasswordRepository.Failure f) {
                post(now().state, false, result == Result.NONE ? Result.LOAD_FAILED : result);
            }
        });
    }

    public void load() { reload(Result.NONE); }

    private PasswordRepository.Callback<Boolean> done(Result verified, Result unverified) {
        return new PasswordRepository.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) { reload(Boolean.TRUE.equals(ok) ? verified : unverified); }
            @Override public void onFailure(PasswordRepository.Failure f) { reload(resultOf(f)); }
        };
    }

    public void change(String newPassword, String confirm) {
        if (now().busy) return;
        if (!newPassword.equals(confirm)) { post(now().state, false, Result.NOT_SAME); return; }
        post(now().state, true, Result.NONE);
        repo.change(service, newPassword, done(Result.CHANGED, Result.CHANGED_UNVERIFIED));
    }

    public void reset() {
        if (now().busy) return;
        post(now().state, true, Result.NONE);
        repo.reset(service, done(Result.RESET_DONE, Result.RESET_UNVERIFIED));
    }

    public void repair(String username, String password) {
        if (now().busy) return;
        post(now().state, true, Result.NONE);
        repo.repair(service, username, password, done(Result.REPAIRED, Result.REPAIRED_UNVERIFIED));
    }

    static Result resultOf(PasswordRepository.Failure f) {
        switch (f) {
            case WEAK_PASSWORD: return Result.WEAK_PASSWORD;
            case MISMATCH: return Result.MISMATCH;
            case BUSY: return Result.BUSY;
            case UNREACHABLE: return Result.UNREACHABLE;
            case REJECTED: return Result.REJECTED;
            default: return Result.FAILED;
        }
    }

    public static final class Factory implements ViewModelProvider.Factory {
        private final String service;

        public Factory(String service) { this.service = service; }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass.isAssignableFrom(ServicePasswordViewModel.class)) {
                return (T) new ServicePasswordViewModel(new PasswordRepositoryImpl(), service);
            }
            throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
        }
    }
}
