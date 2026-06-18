package org.iiab.controller.rootfs.domain;

/**
 * Business rules for resolving a rootfs size.
 *
 * <p>Policy: prefer the live size, but only if it is plausible. A rootfs image
 * is never zero, negative, smaller than {@value #MIN_PLAUSIBLE_BYTES} bytes, nor
 * larger than {@value #MAX_PLAUSIBLE_BYTES} bytes. When the live value is missing
 * or implausible, fall back to the known-good value.
 *
 * <p>Pure domain logic: no Android and no networking dependencies, so it is
 * fully unit-testable on the JVM.
 */
public final class GetRootfsSizeUseCase {

    /** 100 MiB — anything smaller is treated as a bogus/partial response. */
    static final long MIN_PLAUSIBLE_BYTES = 100L * 1024 * 1024;
    /** 10 GiB — anything larger is treated as absurd for a rootfs image. */
    static final long MAX_PLAUSIBLE_BYTES = 10L * 1024 * 1024 * 1024;

    private final RootfsRepository repository;

    public GetRootfsSizeUseCase(RootfsRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolves the size for a tier+abi, applying validation and fallback rules.
     * Never returns {@code null}.
     */
    public Rootfs execute(RootfsTier tier, RootfsAbi abi) {
        return execute(tier, abi, true);
    }

    /**
     * Resolves the size for a tier+abi. When {@code attemptLive} is {@code false}
     * (e.g. the device is known to be offline), the live lookup is skipped and the
     * fallback is returned directly — avoiding a pointless network timeout.
     * Never returns {@code null}.
     */
    public Rootfs execute(RootfsTier tier, RootfsAbi abi, boolean attemptLive) {
        if (attemptLive) {
            Rootfs live = repository.fetchLive(tier, abi);
            if (live != null && live.isLive() && isPlausible(live.sizeBytes())) {
                return live;
            }
        }
        return repository.fallback(tier, abi);
    }

    /** Accepts only sizes within the plausible range; rejects zero, negative and absurd values. */
    static boolean isPlausible(long bytes) {
        return bytes >= MIN_PLAUSIBLE_BYTES && bytes <= MAX_PLAUSIBLE_BYTES;
    }
}
