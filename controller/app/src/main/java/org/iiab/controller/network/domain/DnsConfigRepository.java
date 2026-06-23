/*
 * ============================================================================
 * Name        : DnsConfigRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain port: persistence of the user's DNS choice.
 * ============================================================================
 */
package org.iiab.controller.network.domain;

/**
 * Domain port for persisting the user's DNS choice. The Data layer backs this with
 * SharedPreferences. Semantics support the "Setup DNS" checkbox:
 * <ul>
 *   <li>custom disabled (default) &rarr; effective config is {@link DnsConfig#defaults()};</li>
 *   <li>custom enabled &rarr; effective config is the saved custom config.</li>
 * </ul>
 */
public interface DnsConfigRepository {

    /** Config to actually apply: saved custom when enabled, otherwise {@link DnsConfig#defaults()}. */
    DnsConfig loadEffective();

    /** The saved custom config (or {@link DnsConfig#defaults()} if none saved yet). */
    DnsConfig loadCustom();

    /** Whether the user enabled custom DNS (the "Setup DNS" check). */
    boolean isCustomEnabled();

    /** Persist {@code config} as the custom config and mark custom DNS enabled. */
    void saveCustom(DnsConfig config);

    /** Turn custom DNS off (revert to defaults). Used on test failure / unchecking. */
    void disableCustom();
}
