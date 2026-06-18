package org.iiab.controller.deviceinfo.data;

import android.os.Build;

import org.iiab.controller.deviceinfo.domain.DeviceAbiProvider;

/**
 * Data-layer {@link DeviceAbiProvider} backed by {@link android.os.Build}.
 *
 * <p>{@code Build.SUPPORTED_*_ABIS} are populated from device system properties
 * (the {@code ro.product.cpu.abilist*} family) at runtime init, so they reflect
 * the hardware's capabilities even when the current process is 32-bit. That is
 * exactly what lets us report the real device architecture for a 32-bit app
 * running on a 64-bit device.
 */
public final class BuildDeviceAbiProvider implements DeviceAbiProvider {

    @Override
    public String[] supported64BitAbis() {
        return Build.SUPPORTED_64_BIT_ABIS;
    }

    @Override
    public String[] supported32BitAbis() {
        return Build.SUPPORTED_32_BIT_ABIS;
    }

    @Override
    public String[] allSupportedAbis() {
        return Build.SUPPORTED_ABIS;
    }
}
