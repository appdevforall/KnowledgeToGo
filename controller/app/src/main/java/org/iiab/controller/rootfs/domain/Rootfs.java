package org.iiab.controller.rootfs.domain;

/**
 * Domain entity describing a downloadable rootfs image and its size.
 *
 * <p>Immutable value object. {@code sizeBytes} is the size in bytes; a value
 * {@code <= 0} means "unknown / not available". {@code live} indicates whether
 * the size was obtained from the network ({@code true}) or from a hardcoded
 * fallback ({@code false}).
 *
 * <p>Pure domain type: no Android and no networking dependencies.
 */
public final class Rootfs {

    private final RootfsTier tier;
    private final RootfsAbi abi;
    private final String url;
    private final long sizeBytes;
    private final boolean live;

    public Rootfs(RootfsTier tier, RootfsAbi abi, String url, long sizeBytes, boolean live) {
        this.tier = tier;
        this.abi = abi;
        this.url = url;
        this.sizeBytes = sizeBytes;
        this.live = live;
    }

    public RootfsTier tier() {
        return tier;
    }

    public RootfsAbi abi() {
        return abi;
    }

    public String url() {
        return url;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /** {@code true} if the size came from the network; {@code false} if it is a fallback. */
    public boolean isLive() {
        return live;
    }

    @Override
    public String toString() {
        return "Rootfs{" + tier + ", " + abi + ", sizeBytes=" + sizeBytes + ", live=" + live + '}';
    }
}
