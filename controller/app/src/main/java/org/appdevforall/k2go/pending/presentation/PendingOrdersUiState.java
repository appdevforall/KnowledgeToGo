/*
 * ============================================================================
 * Name        : PendingOrdersUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable view state for the Pending downloads screen (ADFA-5169):
 *               the queued orders to show, and whether a download is running now
 *               (so the screen can offer a link to the live index).
 * ============================================================================
 */
package org.appdevforall.k2go.pending.presentation;

import org.appdevforall.k2go.pending.domain.PendingOrder;

import java.util.Collections;
import java.util.List;

public final class PendingOrdersUiState {

    public final boolean loading;
    public final List<PendingOrder> orders;
    public final boolean somethingRunning;

    private PendingOrdersUiState(boolean loading, List<PendingOrder> orders, boolean somethingRunning) {
        this.loading = loading;
        this.orders = orders;
        this.somethingRunning = somethingRunning;
    }

    public static PendingOrdersUiState loading() {
        return new PendingOrdersUiState(true, Collections.emptyList(), false);
    }

    public static PendingOrdersUiState loaded(List<PendingOrder> orders, boolean somethingRunning) {
        return new PendingOrdersUiState(false,
                orders == null ? Collections.<PendingOrder>emptyList() : orders, somethingRunning);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }
}
