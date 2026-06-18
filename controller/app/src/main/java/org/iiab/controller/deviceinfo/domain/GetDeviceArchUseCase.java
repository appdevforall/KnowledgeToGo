package org.iiab.controller.deviceinfo.domain;

/**
 * Resolves the <em>real device</em> CPU architecture, independent of the running
 * app's own ABI.
 *
 * <p>Why this exists: a 32-bit build of the app can be installed on a 64-bit
 * device (a common way to test the 32-bit path without 32-bit hardware). In that
 * case the app's own ABI is 32-bit, but the device is still 64-bit. Device-facing
 * UI (the dashboard "device architecture") must report the hardware truth, so we
 * derive it from the device's supported-ABI lists rather than the app's
 * {@code nativeLibraryDir}.
 *
 * <p>Rule: prefer the primary 64-bit ABI when the device supports any; otherwise
 * the primary 32-bit ABI; otherwise the first of the generic list. Pure domain
 * logic — no Android dependencies, fully unit-testable on the JVM.
 */
public final class GetDeviceArchUseCase {

    private final DeviceAbiProvider provider;

    public GetDeviceArchUseCase(DeviceAbiProvider provider) {
        this.provider = provider;
    }

    /** Returns the device's primary ABI (e.g. {@code "arm64-v8a"}), or {@code "unknown"}. */
    public String execute() {
        String abi = first(provider.supported64BitAbis());
        if (abi != null) {
            return abi;
        }
        abi = first(provider.supported32BitAbis());
        if (abi != null) {
            return abi;
        }
        abi = first(provider.allSupportedAbis());
        if (abi != null) {
            return abi;
        }
        return "unknown";
    }

    /** First non-empty entry of an ABI array, or {@code null}. */
    private static String first(String[] abis) {
        if (abis == null) {
            return null;
        }
        for (String abi : abis) {
            if (abi != null && !abi.isEmpty()) {
                return abi;
            }
        }
        return null;
    }
}
