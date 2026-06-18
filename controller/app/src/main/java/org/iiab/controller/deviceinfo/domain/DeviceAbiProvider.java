package org.iiab.controller.deviceinfo.domain;

/**
 * Domain port exposing the ABIs the physical device supports.
 *
 * <p>These lists are device-level (what the hardware/OS can run), independent of
 * the current app's own ABI. The Data layer implements this by reading
 * {@code android.os.Build}; the domain never touches Android types.
 *
 * <p>Each method returns the ABI identifiers (e.g. {@code "arm64-v8a"},
 * {@code "armeabi-v7a"}) most-preferred first, or an empty array if none.
 */
public interface DeviceAbiProvider {

    /** 64-bit ABIs the device supports (empty on a 32-bit-only device). */
    String[] supported64BitAbis();

    /** 32-bit ABIs the device supports. */
    String[] supported32BitAbis();

    /** All ABIs the device supports, most-preferred first. */
    String[] allSupportedAbis();
}
