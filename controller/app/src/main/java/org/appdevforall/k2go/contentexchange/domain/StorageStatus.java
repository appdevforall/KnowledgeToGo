package org.appdevforall.k2go.contentexchange.domain;

import java.util.Collections;
import java.util.List;

/**
 * The box content volume's storage summary, mapped from the core
 * {@code GET /v1/storage} ({volume, totalBytes, freeBytes, usedByContentBytes,
 * perApp:[{app, bytes}]}).
 *
 * <p>{@code /v1/storage} is added by a parallel core change and may not be
 * reachable yet; {@link #available()} is {@code false} then, and the manager
 * degrades to showing content without the free-disk figure (never an error). An
 * {@link #unavailable()} instance carries this state.
 *
 * <p>Note: this is the BOX volume as the core sees it. The phone's own free disk
 * (StatFs) is an app-side concern for the later export-preflight slice, not here.
 *
 * <p>Immutable value object. Pure domain type.
 */
public final class StorageStatus {

    private final boolean available;
    private final String volume;
    private final long totalBytes;
    private final long freeBytes;
    private final long usedByContentBytes;
    private final List<AppUsage> perApp;

    private StorageStatus(boolean available, String volume, long totalBytes, long freeBytes,
                          long usedByContentBytes, List<AppUsage> perApp) {
        this.available = available;
        this.volume = volume;
        this.totalBytes = totalBytes;
        this.freeBytes = freeBytes;
        this.usedByContentBytes = usedByContentBytes;
        this.perApp = Collections.unmodifiableList(perApp);
    }

    public static StorageStatus of(String volume, long totalBytes, long freeBytes,
                                   long usedByContentBytes, List<AppUsage> perApp) {
        return new StorageStatus(true, volume, totalBytes, freeBytes, usedByContentBytes, perApp);
    }

    /** The "could not read /v1/storage" state: no figures, {@code available == false}. */
    public static StorageStatus unavailable() {
        return new StorageStatus(false, null, 0L, 0L, 0L, Collections.emptyList());
    }

    /** {@code true} when the core returned a storage summary; {@code false} when it was unreachable. */
    public boolean available() {
        return available;
    }

    public String volume() {
        return volume;
    }

    public long totalBytes() {
        return totalBytes;
    }

    /** Free bytes on the box content volume; meaningful only when {@link #available()}. */
    public long freeBytes() {
        return freeBytes;
    }

    public long usedByContentBytes() {
        return usedByContentBytes;
    }

    /** Per-app byte usage (never null; empty when unavailable). */
    public List<AppUsage> perApp() {
        return perApp;
    }
}
