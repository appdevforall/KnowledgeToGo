package org.iiab.controller.rootfs.presentation;

import org.iiab.controller.rootfs.domain.Rootfs;

/**
 * Immutable UI state for the rootfs-size feature, exposed by {@link RootfsViewModel}.
 *
 * <p>Presentation-layer type. {@code label} is already formatted for display
 * (e.g. "1.3 GiB"); {@code live} lets the UI distinguish a live value from a
 * fallback (e.g. show an "(offline)" hint).
 */
public final class RootfsUiState {

    public enum Status {LOADING, SUCCESS, ERROR}

    public final Status status;
    public final String label;
    public final boolean live;
    public final Rootfs rootfs;
    public final String error;

    private RootfsUiState(Status status, String label, boolean live, Rootfs rootfs, String error) {
        this.status = status;
        this.label = label;
        this.live = live;
        this.rootfs = rootfs;
        this.error = error;
    }

    public static RootfsUiState loading() {
        return new RootfsUiState(Status.LOADING, "…", false, null, null);
    }

    public static RootfsUiState success(Rootfs rootfs, String label) {
        return new RootfsUiState(Status.SUCCESS, label, rootfs.isLive(), rootfs, null);
    }

    public static RootfsUiState error(String message) {
        return new RootfsUiState(Status.ERROR, "—", false, null, message);
    }
}
