/*
 * ============================================================================
 * Name        : DnsSettingsUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable UI state for the Setup DNS panel.
 * ============================================================================
 */
package org.iiab.controller.network.presentation;

/**
 * Immutable UI state for the Setup DNS panel, exposed by {@link DnsSettingsViewModel}.
 * {@code customEnabled} drives the checkbox; {@code primary}/{@code secondary} prefill
 * the fields; {@code status} + {@code message} drive the result indicator.
 */
public final class DnsSettingsUiState {

    public enum Status { IDLE, TESTING, APPLIED, INVALID, UNREACHABLE }

    public final boolean customEnabled;
    public final String primary;
    public final String secondary;
    public final Status status;
    public final String message;

    private DnsSettingsUiState(boolean customEnabled, String primary, String secondary, Status status, String message) {
        this.customEnabled = customEnabled;
        this.primary = primary == null ? "" : primary;
        this.secondary = secondary == null ? "" : secondary;
        this.status = status;
        this.message = message;
    }

    public static DnsSettingsUiState idle(boolean customEnabled, String primary, String secondary) {
        return new DnsSettingsUiState(customEnabled, primary, secondary, Status.IDLE, null);
    }

    public static DnsSettingsUiState testing(String primary, String secondary) {
        return new DnsSettingsUiState(true, primary, secondary, Status.TESTING, null);
    }

    public static DnsSettingsUiState applied(String primary, String secondary) {
        return new DnsSettingsUiState(true, primary, secondary, Status.APPLIED, null);
    }

    public static DnsSettingsUiState invalid(String primary, String secondary, String message) {
        return new DnsSettingsUiState(true, primary, secondary, Status.INVALID, message);
    }

    /** Probe failed: the use case reverted to defaults, so the panel shows defaults again. */
    public static DnsSettingsUiState unreachable(String defaultPrimary, String defaultSecondary) {
        return new DnsSettingsUiState(false, defaultPrimary, defaultSecondary, Status.UNREACHABLE,
                "DNS did not respond — reverted to defaults");
    }
}
