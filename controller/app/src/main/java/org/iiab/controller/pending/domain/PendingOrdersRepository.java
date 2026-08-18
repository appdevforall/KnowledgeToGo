/*
 * ============================================================================
 * Name        : PendingOrdersRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain port for listing and cancelling queued content orders.
 *               The data layer maps each type to its wishlist (ADFA-5169).
 * ============================================================================
 */
package org.iiab.controller.pending.domain;

import java.util.List;

/**
 * The abstraction the domain owns for queued content orders; the data layer provides
 * the implementation. The domain never learns <em>where</em> the orders are stored.
 *
 * <p>Implementations must never throw: {@link #list()} returns an empty list when
 * nothing is queued (or a wishlist cannot be read), and {@link #cancel} is a no-op
 * when the order is already gone.
 */
public interface PendingOrdersRepository {

    /** Every queued content order across the live content types, or empty. */
    List<PendingOrder> list();

    /** Removes one queued order from its wishlist. The rest are untouched. */
    void cancel(PendingOrder order);
}
