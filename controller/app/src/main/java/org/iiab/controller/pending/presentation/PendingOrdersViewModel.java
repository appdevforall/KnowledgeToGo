/*
 * ============================================================================
 * Name        : PendingOrdersViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Presentation ViewModel for the Pending downloads screen (ADFA-5169).
 *               Reads the queued orders off the main thread and exposes a
 *               PendingOrdersUiState stream; cancel() removes one order and reloads.
 *               Cancel is user-driven only — nothing is cancelled automatically.
 * ============================================================================
 */
package org.iiab.controller.pending.presentation;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.pending.domain.PendingOrder;
import org.iiab.controller.pending.domain.PendingOrdersRepository;
import org.iiab.controller.system.data.PendingContent;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PendingOrdersViewModel extends ViewModel {

    private final PendingOrdersRepository repository;
    private final Context app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<PendingOrdersUiState> state =
            new MutableLiveData<>(PendingOrdersUiState.loading());

    public PendingOrdersViewModel(PendingOrdersRepository repository, Context appContext) {
        this.repository = repository;
        this.app = appContext.getApplicationContext();
    }

    public LiveData<PendingOrdersUiState> state() {
        return state;
    }

    /** Re-reads the queued orders (and whether a download is running now). */
    public void refresh() {
        state.postValue(PendingOrdersUiState.loading());
        executor.execute(this::readAndPost);
    }

    /** Removes one queued order, then reloads. No automatic cancellation anywhere. */
    public void cancel(PendingOrder order) {
        executor.execute(() -> {
            repository.cancel(order);
            readAndPost();
        });
    }

    private void readAndPost() {
        List<PendingOrder> orders = repository.list();
        boolean running = PendingContent.anyRunning(app);
        state.postValue(PendingOrdersUiState.loaded(orders, running));
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
