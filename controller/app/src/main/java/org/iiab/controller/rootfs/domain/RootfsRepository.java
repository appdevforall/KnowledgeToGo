package org.iiab.controller.rootfs.domain;

/**
 * Domain port for obtaining rootfs size information.
 *
 * <p>This is the abstraction the domain owns; the Data layer provides the
 * implementation. The domain never learns <em>how</em> sizes are fetched.
 *
 * <p>Implementations must never throw: {@link #fetchLive} returns an entity
 * with {@code sizeBytes <= 0} when the network is unavailable, and
 * {@link #fallback} always returns a known-good value.
 */
public interface RootfsRepository {

    /**
     * Attempts to read the live size from the Deploy server.
     *
     * @return a {@link Rootfs} whose {@code sizeBytes} is the live value, or
     *         {@code <= 0} (and {@code live == false}) if it could not be read.
     */
    Rootfs fetchLive(RootfsTier tier, RootfsAbi abi);

    /**
     * Returns a hardcoded, known-good size for offline / failure scenarios.
     * The concrete values live in the Data layer; the domain only knows they exist.
     */
    Rootfs fallback(RootfsTier tier, RootfsAbi abi);
}
