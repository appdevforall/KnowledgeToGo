package org.iiab.controller.rootfs.domain;

/**
 * Application Binary Interfaces the rootfs is published for.
 *
 * <p>The {@link #id()} matches the suffix used on the Deploy server filenames
 * (e.g. {@code latest_basic_arm64-v8a.meta4}).
 *
 * <p>Pure domain type: no Android and no networking dependencies.
 */
public enum RootfsAbi {
    ARM64_V8A("arm64-v8a"),
    ARMEABI_V7A("armeabi-v7a");

    private final String id;

    RootfsAbi(String id) {
        this.id = id;
    }

    /** Server-side filename suffix for this ABI. */
    public String id() {
        return id;
    }
}
